package com.acme.catalog.ops;

import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.indexer.IndexAdmin;
import com.acme.catalog.outbox.OutboxStore;
import com.acme.catalog.query.hibernatesearch.HibernateSearchAdmin;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Health for the search path, reported so that it can never take a working instance
 * out of the load balancer.
 *
 * <h2>Why an unreachable search cluster is not DOWN</h2>
 * The whole design exists so that a search outage degrades latency, not correctness:
 * reads fall back to MySQL and writes never touched OpenSearch in the first place. An
 * instance in that state is serving correct responses and must keep receiving traffic.
 * Reporting DOWN would remove it — turning a latency problem into an outage, which is
 * exactly the failure mode the architecture was chosen to avoid.
 * <p>
 * So this contributes a custom {@code DEGRADED} status, mapped to HTTP 200, and the
 * readiness group deliberately excludes it (see application.yml). The details are
 * still there for a human, and the metrics are still the thing to alert on.
 */
@Component("search")
public class SearchHealthIndicator implements HealthIndicator {

    /** Serving correct results, more slowly, from the fallback. */
    static final Status DEGRADED = new Status("DEGRADED", "Search unavailable; serving from MySQL");

    private final IndexAdmin indexAdmin;
    private final OutboxStore outbox;
    private final HibernateSearchAdmin hibernateSearch;
    private final SearchProperties properties;

    public SearchHealthIndicator(IndexAdmin indexAdmin, OutboxStore outbox,
                                 HibernateSearchAdmin hibernateSearch, SearchProperties properties) {
        this.indexAdmin = indexAdmin;
        this.outbox = outbox;
        this.hibernateSearch = hibernateSearch;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Health.Builder health = new Health.Builder();
        health.withDetail("engine", properties.engine());

        boolean reachable = true;
        try {
            long handRolled = indexAdmin.documentCount(properties.opensearch().readAlias());
            reachable = handRolled >= 0;
            health.withDetail("handRolled", details(handRolled, outbox.backlog(),
                    outbox.oldestPendingAge().toSeconds()));
        } catch (RuntimeException e) {
            reachable = false;
            health.withDetail("handRolled", "unreachable: " + e.getClass().getSimpleName());
        }

        try {
            health.withDetail("hibernateSearch", details(hibernateSearch.documentCount(),
                    hibernateSearch.backlog(), hibernateSearch.oldestPendingAge().toSeconds()));
        } catch (RuntimeException e) {
            health.withDetail("hibernateSearch", "unreachable: " + e.getClass().getSimpleName());
        }

        return (reachable ? health.up() : health.status(DEGRADED))
                .withDetail("fallbackToMysql", properties.fallbackToMysql())
                .build();
    }

    private static String details(long documents, long backlog, long lagSeconds) {
        return "documents=" + documents + " backlog=" + backlog + " lagSeconds=" + lagSeconds;
    }
}
