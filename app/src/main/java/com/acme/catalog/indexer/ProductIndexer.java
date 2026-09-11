package com.acme.catalog.indexer;

import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.projection.ProductDocument;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.VersionType;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The only place that writes to OpenSearch.
 *
 * <h2>Why external versioning matters here</h2>
 * Every document carries {@code product.aggregate_version} as its external version
 * with {@code external_gte} semantics. OpenSearch then refuses to install a document
 * whose version is lower than the one already stored. That single flag buys:
 * <ul>
 *   <li>safe concurrency -- two relay workers processing the same aggregate cannot
 *       interleave into a stale result;</li>
 *   <li>safe replay -- redelivering an outbox row is a no-op or a newer write;</li>
 *   <li>safe backfill -- a full reindex can run while live traffic writes to the
 *       same index, because an old snapshot row simply loses to the live version;</li>
 *   <li>safe replica reads -- the relay may read the aggregate from a lagging MySQL
 *       replica without risk of overwriting a fresher document.</li>
 * </ul>
 * The price: a 409 in the bulk response is a normal outcome, not an error.
 */
@Component
public class ProductIndexer {

    private static final Logger log = LoggerFactory.getLogger(ProductIndexer.class);

    private final OpenSearchClient client;
    private final SearchProperties properties;
    private final MeterRegistry meters;

    public ProductIndexer(OpenSearchClient client, SearchProperties properties, MeterRegistry meters) {
        this.client = client;
        this.properties = properties;
        this.meters = meters;
    }

    public record IndexResult(int indexed, int deleted, int staleSkipped, List<String> failures) {
        public boolean ok() {
            return failures.isEmpty();
        }
    }

    public IndexResult write(Collection<ProductDocument> documents, Collection<Long> deletedIds) {
        return write(documents, deletedIds, properties.opensearch().writeAlias());
    }

    /**
     * @param target the write alias in normal operation; a concrete index name only
     *               while backfilling a not-yet-live index.
     */
    public IndexResult write(Collection<ProductDocument> documents, Collection<Long> deletedIds, String target) {
        if (documents.isEmpty() && deletedIds.isEmpty()) {
            return new IndexResult(0, 0, 0, List.of());
        }
        List<BulkOperation> operations = new ArrayList<>(documents.size() + deletedIds.size());
        for (ProductDocument document : documents) {
            operations.add(BulkOperation.of(operation -> operation.index(index -> index
                    .id(Long.toString(document.id()))
                    .document(document)
                    .versionType(VersionType.ExternalGte)
                    .version(document.version()))));
        }
        for (Long id : deletedIds) {
            // Deletes carry no aggregate version -- the row is gone. See spec 9.5 for
            // the soft-delete alternative when delete/recreate races are possible.
            operations.add(BulkOperation.of(operation -> operation.delete(delete -> delete.id(Long.toString(id)))));
        }

        BulkResponse response;
        Timer.Sample sample = Timer.start(meters);
        try {
            response = client.bulk(bulk -> bulk.index(target).operations(operations));
        } catch (IOException e) {
            throw new UncheckedIOException("Bulk write to " + target + " failed", e);
        } finally {
            sample.stop(meters.timer("catalog.index.bulk", "target", target));
        }

        int indexed = 0;
        int deleted = 0;
        int stale = 0;
        List<String> failures = new ArrayList<>();
        for (BulkResponseItem item : response.items()) {
            if (item.error() == null) {
                if (item.operationType() != null && "delete".equals(item.operationType().jsonValue())) {
                    deleted++;
                } else {
                    indexed++;
                }
            } else if (item.status() == 409) {
                // A newer version of this document is already indexed. Expected.
                stale++;
            } else {
                failures.add(item.id() + ": " + item.error().reason());
            }
        }

        meters.counter("catalog.index.documents", "outcome", "indexed").increment(indexed);
        meters.counter("catalog.index.documents", "outcome", "deleted").increment(deleted);
        meters.counter("catalog.index.documents", "outcome", "stale").increment(stale);
        meters.counter("catalog.index.documents", "outcome", "failed").increment(failures.size());
        if (!failures.isEmpty()) {
            log.warn("Bulk write to {} had {} failures, first: {}", target, failures.size(), failures.get(0));
        }
        return new IndexResult(indexed, deleted, stale, failures);
    }
}
