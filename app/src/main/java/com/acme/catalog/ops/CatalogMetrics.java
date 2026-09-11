package com.acme.catalog.ops;

import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.indexer.IndexAdmin;
import com.acme.catalog.outbox.OutboxStore;
import com.acme.catalog.query.hibernatesearch.HibernateSearchAdmin;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Every gauge in the service, registered in one place.
 *
 * <h2>The metric catalogue</h2>
 * <table>
 *   <caption>What exists and what it answers</caption>
 *   <tr><th>Metric</th><th>Question it answers</th></tr>
 *   <tr><td>{@code catalog.search.engine{engine,outcome}} (timer)</td>
 *       <td>How fast is each engine, and is it erroring?</td></tr>
 *   <tr><td>{@code catalog.search.request{engine,outcome}} (timer)</td>
 *       <td>What does the caller actually experience, hydration included?</td></tr>
 *   <tr><td>{@code catalog.search.hydration{engine}} (timer)</td>
 *       <td>What does the second hop cost?</td></tr>
 *   <tr><td>{@code catalog.search.shadow{candidate,match}} (counter)</td>
 *       <td>Do the engines agree on real traffic? The gate for cutover.</td></tr>
 *   <tr><td>{@code catalog.search.fallback{engine}} (counter)</td>
 *       <td>How often is the search cluster failing us?</td></tr>
 *   <tr><td>{@code catalog.search.stale_dropped{engine}} (counter)</td>
 *       <td>How often does the index name rows MySQL no longer has?</td></tr>
 *   <tr><td>{@code catalog.search.refill{engine}} (counter)</td>
 *       <td>How often was a page short enough to need a second search?</td></tr>
 *   <tr><td>{@code catalog.search.deep_paging_rejected{engine}} (counter)</td>
 *       <td>Are callers hitting the paging ceiling?</td></tr>
 *   <tr><td>{@code catalog.outbox.backlog{pipeline}} (gauge)</td>
 *       <td>How much indexing work is queued?</td></tr>
 *   <tr><td>{@code catalog.outbox.lag.seconds{pipeline}} (gauge)</td>
 *       <td><b>How stale is the index?</b> The one to alert on.</td></tr>
 *   <tr><td>{@code catalog.outbox.aborted{pipeline}} (gauge)</td>
 *       <td>Are there poison rows nobody is retrying?</td></tr>
 *   <tr><td>{@code catalog.outbox.batch} (timer)</td>
 *       <td>How long does one relay cycle take?</td></tr>
 *   <tr><td>{@code catalog.index.bulk{target}} (timer)</td>
 *       <td>How long does a bulk request take?</td></tr>
 *   <tr><td>{@code catalog.index.documents{outcome}} (counter)</td>
 *       <td>indexed / deleted / stale / failed</td></tr>
 *   <tr><td>{@code catalog.index.documents.count{pipeline}} (gauge)</td>
 *       <td>Does the index hold as many documents as MySQL has rows?</td></tr>
 *   <tr><td>{@code catalog.db.rows{table}} (gauge)</td>
 *       <td>The denominator for the line above.</td></tr>
 * </table>
 *
 * Spring Boot's actuator adds {@code http.server.requests}, {@code hikaricp.*} and the
 * JVM meters on its own; those are not repeated here.
 *
 * <h2>Why the gauges are cached</h2>
 * Each one costs a database or cluster round trip. Prometheus scrapes every node, and
 * a gauge that queries on every scrape turns monitoring into load. A short TTL keeps
 * them honest without letting the scrape interval drive query volume.
 */
@Component
public class CatalogMetrics {

    private static final Logger log = LoggerFactory.getLogger(CatalogMetrics.class);
    private static final long CACHE_TTL_MILLIS = 10_000;

    /**
     * Strong references to every gauge's state object.
     * <p>
     * Micrometer holds the object a gauge reads from by WEAK reference, so a state
     * object that only exists as a local variable is collected and the gauge silently
     * reports NaN forever. This list is the fix, and it is easy to leave out because
     * everything still compiles and starts.
     */
    private final List<Cached> gaugeState = new ArrayList<>();

    public CatalogMetrics(MeterRegistry meters,
                          OutboxStore outbox,
                          HibernateSearchAdmin hibernateSearch,
                          IndexAdmin indexAdmin,
                          SearchProperties properties) {

        gauge(meters, "catalog.outbox.backlog", "pipeline", "hand-rolled",
                "Outbox rows awaiting indexing", () -> (double) outbox.backlog());
        gauge(meters, "catalog.outbox.lag.seconds", "pipeline", "hand-rolled",
                "Age of the oldest unprocessed change", () -> outbox.oldestPendingAge().toMillis() / 1000d);

        gauge(meters, "catalog.outbox.backlog", "pipeline", "hibernate-search",
                "Hibernate Search outbox rows awaiting processing", () -> (double) hibernateSearch.backlog());
        gauge(meters, "catalog.outbox.lag.seconds", "pipeline", "hibernate-search",
                "Age of the oldest unprocessed Hibernate Search event",
                () -> hibernateSearch.oldestPendingAge().toMillis() / 1000d);
        gauge(meters, "catalog.outbox.aborted", "pipeline", "hibernate-search",
                "Events Hibernate Search gave up on", () -> (double) hibernateSearch.aborted());

        gauge(meters, "catalog.index.documents.count", "pipeline", "hand-rolled",
                "Documents in the hand-rolled index",
                () -> (double) indexAdmin.documentCount(properties.opensearch().readAlias()));
        gauge(meters, "catalog.index.documents.count", "pipeline", "hibernate-search",
                "Documents in the Hibernate Search index",
                () -> (double) hibernateSearch.documentCount());
        // Hibernate Search publishes no metrics of its own, so its processor is observed
        // through the agent table it maintains. Without this there is no signal at all
        // that its event processor is alive.
        for (String state : new String[]{"RUNNING", "WAITING", "SUSPENDED"}) {
            gauge(meters, "catalog.hs.agents", "state", state,
                    "Hibernate Search processors registered in this state",
                    () -> (double) hibernateSearch.agentsByState().getOrDefault(state, 0L));
        }
        gauge(meters, "catalog.hs.agents.expired", "pipeline", "hibernate-search",
                "Registered processors whose lease has lapsed; work assigned to them stalls",
                () -> (double) hibernateSearch.expiredAgents());

        gauge(meters, "catalog.index.size.bytes", "pipeline", "hand-rolled",
                "Index size on disk",
                () -> (double) indexAdmin.indexSizeBytes(properties.opensearch().readAlias()));
        gauge(meters, "catalog.index.size.bytes", "pipeline", "hibernate-search",
                "Index size on disk",
                () -> (double) indexAdmin.indexSizeBytes(HibernateSearchAdmin.READ_ALIAS));

        gauge(meters, "catalog.db.rows", "table", "product",
                "Rows in the product table", () -> (double) outbox.productRowCount());

        // Documents actually written, per pipeline. A COUNTER, not a gauge: the whole
        // point is rate(), and dividing that rate by the mutation rate gives indexing
        // amplification -- the number that makes a fan-out visible.
        writeCounter(meters, "hand-rolled",
                () -> (double) indexAdmin.indexWriteTotal(properties.opensearch().readAlias()));
        writeCounter(meters, "hibernate-search",
                () -> (double) indexAdmin.indexWriteTotal(HibernateSearchAdmin.READ_ALIAS));
    }

    private void writeCounter(MeterRegistry meters, String pipeline, Supplier<Double> supplier) {
        Cached cached = new Cached(supplier, "catalog.index.writes{pipeline=" + pipeline + "}");
        gaugeState.add(cached);
        FunctionCounter.builder("catalog.index.writes", cached, Cached::value)
                .tag("pipeline", pipeline)
                .description("Documents written to this pipeline's index since it was created")
                .register(meters);
    }

    private void gauge(MeterRegistry meters, String name, String tagKey, String tagValue,
                       String description, Supplier<Double> supplier) {
        Cached cached = new Cached(supplier, name + "{" + tagKey + "=" + tagValue + "}");
        gaugeState.add(cached);
        Gauge.builder(name, cached, Cached::value)
                .tag(tagKey, tagValue)
                .description(description)
                .register(meters);
    }

    /**
     * A gauge value that refreshes at most once per TTL, and never propagates a
     * failure: a database blip must not break the scrape that would have told you
     * about the database blip.
     */
    private static final class Cached {

        private final Supplier<Double> supplier;
        private final String name;
        private final AtomicLong refreshedAt = new AtomicLong(0);
        private volatile double last = Double.NaN;

        private Cached(Supplier<Double> supplier, String name) {
            this.supplier = supplier;
            this.name = name;
        }

        double value() {
            long now = System.currentTimeMillis();
            long previous = refreshedAt.get();
            if (now - previous >= CACHE_TTL_MILLIS && refreshedAt.compareAndSet(previous, now)) {
                try {
                    last = supplier.get();
                } catch (RuntimeException e) {
                    log.warn("Gauge {} could not refresh: {}", name, e.toString());
                }
            }
            return last;
        }
    }
}
