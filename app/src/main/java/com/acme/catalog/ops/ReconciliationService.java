package com.acme.catalog.ops;

import com.acme.catalog.config.IndexingProperties;
import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.indexer.IndexAdmin;
import com.acme.catalog.indexer.ReindexService;
import com.acme.catalog.projection.ProductDocument;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TermsQueryField;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drift detection. Any asynchronous projection eventually drifts -- a bug, a lost
 * message, a hand-written UPDATE straight against the database -- so the question is
 * not whether it happens but whether you find out before a customer does.
 * <p>
 * Cheap by construction: compare counts, then compare {@code aggregate_version} for a
 * sample of rows. Repairs go through the outbox, so the repair path is the same code
 * as the normal path.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final OpenSearchClient client;
    private final IndexAdmin indexAdmin;
    private final ReindexService reindexService;
    private final SearchProperties properties;
    private final IndexingProperties indexingProperties;

    public ReconciliationService(NamedParameterJdbcTemplate jdbc, OpenSearchClient client,
                                 IndexAdmin indexAdmin, ReindexService reindexService,
                                 SearchProperties properties, IndexingProperties indexingProperties) {
        this.jdbc = jdbc;
        this.client = client;
        this.indexAdmin = indexAdmin;
        this.reindexService = reindexService;
        this.properties = properties;
        this.indexingProperties = indexingProperties;
    }

    /**
     * @param stale documents whose version is behind MySQL -- the pipeline is late
     * @param ahead documents whose version is <em>greater</em> than MySQL's. Under
     *              AGGREGATE_VERSION this is a benign race: the sample was read, then a
     *              write landed and was indexed. Under MAX_UPDATED_AT it is the
     *              signature of real corruption, because deleting the most recently
     *              updated child row makes the derived version go backwards and
     *              external_gte then rejects the corrected document forever. Either
     *              way it must be visible, because the relay reports success.
     */
    public record ReconciliationReport(long mysqlRows, long indexDocuments, int sampled,
                                       List<Long> missing, List<Long> stale, List<Long> ahead,
                                       int repairsEnqueued) {
    }

    public ReconciliationReport check(int sampleSize, boolean repair) {
        Long mysqlRows = jdbc.queryForObject("SELECT COUNT(*) FROM product", new MapSqlParameterSource(), Long.class);
        long indexDocuments = indexAdmin.documentCount(properties.opensearch().readAlias());

        // ORDER BY RAND() is fine for a few hundred sampled rows on a maintenance
        // schedule; for a large table, sample by id range instead.
        // Must compare against whatever the assembler publishes, or every row looks wrong.
        // READ_TIMESTAMP is the awkward one: the version is when the relay READ the row,
        // which has no counterpart in MySQL. What it does support is a lower bound --
        // the relay must have read the row after the row was last written, so a document
        // whose version predates product.updated_at was built from a state that is now
        // out of date. "Ahead" is then the normal case, not a signal.
        String versionExpression = switch (indexingProperties.versionSource()) {
            case AGGREGATE_VERSION -> "p.aggregate_version";
            case MAX_UPDATED_AT -> """
                    CAST(UNIX_TIMESTAMP(GREATEST(p.updated_at,
                        COALESCE((SELECT MAX(i.updated_at) FROM inventory i
                                    JOIN product_variant pv ON pv.id = i.variant_id
                                   WHERE pv.product_id = p.id), p.updated_at))) * 1000 AS UNSIGNED)""";
            case READ_TIMESTAMP -> "CAST(UNIX_TIMESTAMP(p.updated_at) * 1000000 AS UNSIGNED)";
        };
        boolean aheadIsMeaningful =
                indexingProperties.versionSource() != IndexingProperties.VersionSource.READ_TIMESTAMP;
        List<Map<String, Object>> sample = jdbc.queryForList(
                "SELECT p.id, " + versionExpression + " AS version FROM product p "
                        + "ORDER BY RAND() LIMIT :sampleSize",
                new MapSqlParameterSource("sampleSize", sampleSize));

        Map<Long, Long> expected = new HashMap<>();
        for (Map<String, Object> row : sample) {
            expected.put(((Number) row.get("id")).longValue(), ((Number) row.get("version")).longValue());
        }

        Map<Long, Long> actual = fetchIndexedVersions(expected.keySet().stream().toList());

        List<Long> missing = new ArrayList<>();
        List<Long> stale = new ArrayList<>();
        List<Long> ahead = new ArrayList<>();
        expected.forEach((id, version) -> {
            Long indexedVersion = actual.get(id);
            if (indexedVersion == null) {
                missing.add(id);
            } else if (indexedVersion < version) {
                stale.add(id);
            } else if (indexedVersion > version && aheadIsMeaningful) {
                ahead.add(id);
            }
        });

        int repaired = 0;
        if (repair && !(missing.isEmpty() && stale.isEmpty())) {
            List<Long> broken = new ArrayList<>(missing);
            broken.addAll(stale);
            repaired = reindexService.replayIds(broken);
            log.warn("Reconciliation enqueued {} repairs ({} missing, {} stale)",
                    repaired, missing.size(), stale.size());
        }

        return new ReconciliationReport(mysqlRows == null ? 0 : mysqlRows, indexDocuments,
                expected.size(), missing, stale, ahead, repaired);
    }

    private Map<Long, Long> fetchIndexedVersions(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<FieldValue> values = ids.stream().map(FieldValue::of).toList();
        SearchResponse<ProductDocument> response;
        try {
            response = client.search(search -> search
                            .index(properties.opensearch().readAlias())
                            .size(ids.size())
                            .query(Query.of(query -> query.terms(terms -> terms
                                    .field("id")
                                    .terms(TermsQueryField.of(t -> t.value(values))))))
                            .source(source -> source.filter(filter -> filter.includes("id", "version"))),
                    ProductDocument.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Reconciliation query failed", e);
        }
        Map<Long, Long> versions = new HashMap<>();
        response.hits().hits().forEach(hit -> {
            if (hit.source() != null) {
                versions.put(hit.source().id(), hit.source().version());
            }
        });
        return versions;
    }
}
