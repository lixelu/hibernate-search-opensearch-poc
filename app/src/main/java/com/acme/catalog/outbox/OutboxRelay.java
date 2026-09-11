package com.acme.catalog.outbox;

import com.acme.catalog.config.IndexingProperties;
import com.acme.catalog.indexer.ProductIndexer;
import com.acme.catalog.projection.ProductDocument;
import com.acme.catalog.projection.ProductProjectionAssembler;
import com.acme.catalog.read.ProductAggregate;
import com.acme.catalog.read.ProductAggregateLoader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outbox -> projection -> OpenSearch. The single moving part of the integration.
 *
 * <h2>Order of operations (and why)</h2>
 * <pre>
 *   claim rows  ->  load aggregates  ->  bulk index  ->  delete rows
 * </pre>
 * Claim-then-delete (rather than delete-then-index) makes the pipeline at-least-once
 * across a crash; external versioning makes at-least-once harmless. Deleting first
 * would be at-most-once, i.e. silent data loss in the index on any crash.
 *
 * <h2>Coalescing</h2>
 * Twenty writes to the same product inside one poll window produce twenty outbox
 * rows but exactly one aggregate load and one indexed document. Bursty writers --
 * bulk imports, price feeds -- therefore cost the index almost nothing extra, which
 * is the main reason the payload is not stored in the outbox row.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final ProductAggregateLoader loader;
    private final ProductProjectionAssembler assembler;
    private final ProductIndexer indexer;
    private final IndexingProperties properties;
    private final Timer batchTimer;
    private final String workerId;

    public OutboxRelay(OutboxStore store,
                       ProductAggregateLoader loader,
                       ProductProjectionAssembler assembler,
                       ProductIndexer indexer,
                       IndexingProperties properties,
                       MeterRegistry meters) {
        this.store = store;
        this.loader = loader;
        this.assembler = assembler;
        this.indexer = indexer;
        this.properties = properties;
        this.workerId = ManagementFactory.getRuntimeMXBean().getName();
        this.batchTimer = Timer.builder("catalog.outbox.batch")
                .description("One claim/index/acknowledge cycle")
                .register(meters);
        // Gauges live in CatalogMetrics, so the metric catalogue is readable in one file.
    }

    public record RelayBatch(int claimed, int aggregates, int indexed, int deleted, int stale, int failed) {
        public boolean drainedSomething() {
            return claimed > 0;
        }
    }

    @Scheduled(fixedDelayString = "${catalog.indexing.poll-delay-ms:200}")
    public void poll() {
        if (!properties.relayEnabled()) {
            return;
        }
        try {
            // Keep draining while there is work, so a burst is absorbed in one poll
            // window instead of trickling out one batch per tick.
            RelayBatch batch;
            do {
                batch = drainOnce();
            } while (batch.drainedSomething() && batch.claimed() >= properties.claimBatchSize());
        } catch (Exception e) {
            log.error("Outbox relay poll failed; rows stay claimed until the claim times out", e);
        }
    }

    /** One claim/index/ack cycle. Exposed so tests can drive the pipeline deterministically. */
    public RelayBatch drainOnce() {
        return batchTimer.record(() -> {
            List<OutboxStore.ClaimedEvent> claimed = store.claim(OutboxStore.AGGREGATE_PRODUCT,
                    properties.claimBatchSize(), properties.claimTimeout(), properties.maxAttempts(), workerId);
            if (claimed.isEmpty()) {
                return new RelayBatch(0, 0, 0, 0, 0, 0);
            }

            Set<Long> aggregateIds = new LinkedHashSet<>();
            claimed.forEach(event -> aggregateIds.add(event.aggregateId()));

            Map<Long, ProductAggregate> aggregates = loader.loadByIds(aggregateIds);

            List<ProductDocument> documents = aggregates.values().stream().map(assembler::toDocument).toList();
            // An id the loader did not return no longer exists in MySQL: the same
            // "dirty key" event that carries an update also carries a delete.
            List<Long> deletedIds = aggregateIds.stream().filter(id -> !aggregates.containsKey(id)).toList();

            List<Long> eventIds = claimed.stream().map(OutboxStore.ClaimedEvent::eventId).toList();
            try {
                ProductIndexer.IndexResult result = indexer.write(documents, deletedIds);
                if (result.ok()) {
                    store.complete(eventIds);
                } else {
                    store.releaseWithError(eventIds, String.join(" | ", result.failures()));
                }
                return new RelayBatch(claimed.size(), aggregateIds.size(), result.indexed(),
                        result.deleted(), result.staleSkipped(), result.failures().size());
            } catch (RuntimeException e) {
                log.warn("Indexing batch of {} aggregates failed, releasing for retry", aggregateIds.size(), e);
                store.releaseWithError(eventIds, e.getMessage());
                return new RelayBatch(claimed.size(), aggregateIds.size(), 0, 0, 0, claimed.size());
            }
        });
    }
}
