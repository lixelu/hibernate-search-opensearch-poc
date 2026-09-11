package com.acme.catalog.indexer;

import com.acme.catalog.config.IndexingProperties;
import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.projection.ProductDocument;
import com.acme.catalog.projection.ProductProjectionAssembler;
import com.acme.catalog.read.ProductAggregate;
import com.acme.catalog.read.ProductAggregateLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full rebuild and targeted repair.
 *
 * <h2>Zero-downtime rebuild</h2>
 * <pre>
 *   1. create products-v(N+1) from the mapping resource
 *   2. move the WRITE alias to it        -- live changes now land in the new index
 *   3. backfill by keyset over the PK    -- old rows lose to live ones on version
 *   4. refresh, then move the READ alias -- readers cut over atomically
 *   5. drop products-vN once you are happy
 * </pre>
 * Step 3 is safe next to step 2 only because of external versioning: a backfilled
 * row carrying version 7 cannot overwrite a live write that already stored version 8.
 * <p>
 * The one real caveat: between steps 2 and 4 the OLD index stops receiving updates,
 * so aborting mid-rebuild leaves it behind. The recovery is
 * {@link #replayChangedSince(Instant)} -- move the write alias back and replay the
 * window. Keep the mapping change and the rebuild in the same deploy, and keep the
 * window short.
 */
@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final IndexAdmin indexAdmin;
    private final ProductAggregateLoader loader;
    private final ProductProjectionAssembler assembler;
    private final ProductIndexer indexer;
    private final NamedParameterJdbcTemplate jdbc;
    private final SearchProperties searchProperties;
    private final IndexingProperties indexingProperties;

    public ReindexService(IndexAdmin indexAdmin, ProductAggregateLoader loader,
                          ProductProjectionAssembler assembler, ProductIndexer indexer,
                          NamedParameterJdbcTemplate jdbc, SearchProperties searchProperties,
                          IndexingProperties indexingProperties) {
        this.indexAdmin = indexAdmin;
        this.loader = loader;
        this.assembler = assembler;
        this.indexer = indexer;
        this.jdbc = jdbc;
        this.searchProperties = searchProperties;
        this.indexingProperties = indexingProperties;
    }

    public record ReindexReport(String targetIndex, long documents, long millis, boolean readAliasMoved) {
    }

    public ReindexReport rebuild(boolean moveReadAlias) {
        long startedAt = System.currentTimeMillis();
        String target = indexAdmin.nextIndexName();
        indexAdmin.createIndex(target);
        indexAdmin.pointAlias(searchProperties.opensearch().writeAlias(), target);

        long documents = backfillInto(target);

        indexAdmin.refresh(target);
        if (moveReadAlias) {
            indexAdmin.pointAlias(searchProperties.opensearch().readAlias(), target);
        }
        long millis = System.currentTimeMillis() - startedAt;
        log.info("Rebuilt {} with {} documents in {} ms (read alias moved: {})",
                target, documents, millis, moveReadAlias);
        return new ReindexReport(target, documents, millis, moveReadAlias);
    }

    /** Keyset walk over the primary key: constant cost per page, no OFFSET blow-up. */
    public long backfillInto(String target) {
        long afterId = 0;
        long total = 0;
        while (true) {
            List<Long> ids = jdbc.queryForList("""
                    SELECT id FROM product WHERE id > :afterId ORDER BY id LIMIT :pageSize
                    """, new MapSqlParameterSource()
                    .addValue("afterId", afterId)
                    .addValue("pageSize", indexingProperties.reindexPageSize()), Long.class);
            if (ids.isEmpty()) {
                return total;
            }
            Map<Long, ProductAggregate> aggregates = loader.loadByIds(ids);
            List<ProductDocument> documents = aggregates.values().stream().map(assembler::toDocument).toList();
            for (int offset = 0; offset < documents.size(); offset += indexingProperties.bulkSize()) {
                List<ProductDocument> chunk = documents.subList(offset,
                        Math.min(offset + indexingProperties.bulkSize(), documents.size()));
                indexer.write(chunk, List.of(), target);
            }
            total += documents.size();
            afterId = ids.get(ids.size() - 1);
            if (total % 20_000 == 0) {
                log.info("Backfill progress: {} documents into {}", total, target);
            }
        }
    }

    /**
     * Repair by enqueueing, not by indexing: push the affected keys back through the
     * normal outbox pipeline so there is exactly one code path that writes documents.
     */
    public int replayChangedSince(Instant since) {
        return jdbc.update("""
                INSERT INTO outbox_event (aggregate_type, aggregate_id)
                SELECT 'product', p.id FROM product p WHERE p.updated_at >= :since
                """, new MapSqlParameterSource("since", Timestamp.from(since)));
    }

    public int replayIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                INSERT INTO outbox_event (aggregate_type, aggregate_id)
                SELECT 'product', p.id FROM product p WHERE p.id IN (:ids)
                """, new MapSqlParameterSource("ids", ids));
    }

    public int replayLast(Duration window) {
        return replayChangedSince(Instant.now().minus(window));
    }
}
