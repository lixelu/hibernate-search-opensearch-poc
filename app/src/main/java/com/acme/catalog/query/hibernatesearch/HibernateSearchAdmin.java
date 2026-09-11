package com.acme.catalog.query.hibernatesearch;

import com.acme.catalog.domain.Product;
import com.acme.catalog.indexer.IndexAdmin;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.massindexing.MassIndexer;
import org.hibernate.search.mapper.orm.outboxpolling.OutboxPollingExtension;
import org.hibernate.search.mapper.orm.outboxpolling.mapping.OutboxPollingSearchMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operating Hibernate Search's pipeline: the backfill, and visibility into its outbox.
 * <p>
 * The two pipelines in this project need the same three operations -- backfill,
 * backlog, drift -- so the same runbook applies to whichever one you pick. What
 * changes is who implements them: here they come from the library, in the hand-rolled
 * path they are {@code ReindexService} and {@code ReconciliationService}.
 */
@Service
public class HibernateSearchAdmin {

    private static final Logger log = LoggerFactory.getLogger(HibernateSearchAdmin.class);

    /** The read alias Hibernate Search maintains for the {@code hs-products} index. */
    public static final String READ_ALIAS = "hs-products-read";

    private final EntityManager entityManager;
    private final EntityManagerFactory entityManagerFactory;
    private final NamedParameterJdbcTemplate jdbc;
    private final IndexAdmin indexAdmin;

    public HibernateSearchAdmin(EntityManager entityManager,
                                EntityManagerFactory entityManagerFactory,
                                NamedParameterJdbcTemplate jdbc,
                                IndexAdmin indexAdmin) {
        this.entityManager = entityManager;
        this.entityManagerFactory = entityManagerFactory;
        this.jdbc = jdbc;
        this.indexAdmin = indexAdmin;
    }

    public record MassIndexReport(long documents, long millis) {
    }

    /**
     * The library's equivalent of {@code ReindexService.rebuild}. It drops and rebuilds
     * the index by streaming entities out of MySQL, and it purges first -- so unlike the
     * alias-swap rebuild, the index is incomplete while it runs. Run it against a
     * non-live index, or accept the window.
     */
    @Transactional
    public MassIndexReport massIndex(int threads, int batchSize) throws InterruptedException {
        long startedAt = System.currentTimeMillis();
        MassIndexer indexer = Search.session(entityManager)
                .massIndexer(Product.class)
                .threadsToLoadObjects(threads)
                .batchSizeToLoadObjects(batchSize)
                .idFetchSize(Integer.MIN_VALUE)
                .typesToIndexInParallel(1);
        indexer.startAndWait();
        long millis = System.currentTimeMillis() - startedAt;
        long count = documentCount();
        log.info("Hibernate Search mass index finished: {} documents in {} ms", count, millis);
        return new MassIndexReport(count, millis);
    }

    /** Rows waiting in Hibernate Search's own outbox table. The number to alert on. */
    public long backlog() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM hsearch_outbox_event",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Rows the library has given up on after exhausting its retries. Equivalent to a
     * poison row in the hand-rolled outbox: it stops being retried and stays visible.
     */
    public long aborted() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM hsearch_outbox_event WHERE status = 'ABORTED'",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * The library's recovery API for aborted events.
     *
     * <p>Reference doc 19.3.8: after two failed retries "the event will be marked as
     * aborted. Aborted events won't be processed by the processor." Nothing retries them
     * and nothing escalates. Counting them is not enough -- there has to be a way to put
     * them back once the underlying cause is fixed, and that is this call.
     *
     * <p>{@code reprocessAbortedEvents} is the one to reach for. Clearing is for events
     * whose entities no longer exist, and it discards index updates rather than applying
     * them, so it must be followed by a rebuild.
     */
    public int reprocessAbortedEvents() {
        int count = outboxPolling().reprocessAbortedEvents();
        log.info("Requeued {} aborted Hibernate Search events", count);
        return count;
    }

    /** Discards aborted events. The index stays wrong until something rebuilds it. */
    public int clearAbortedEvents() {
        int count = outboxPolling().clearAllAbortedEvents();
        log.warn("Discarded {} aborted Hibernate Search events; the index is now known to be "
                + "behind for those entities and needs a rebuild", count);
        return count;
    }

    /**
     * Stops or restarts listener-triggered indexing for the whole application.
     *
     * <p>Reference doc 14.2.3: the indexing plan filter exists so indexing can be paused
     * "when importing larger amounts of data". Excluding {@code Object.class} excludes
     * every subtype, which is the documented way to turn indexing off entirely.
     *
     * <p>The filter must be application-wide here. The doc is explicit that session-level
     * filters are unsafe under {@code outbox-polling}, because events are processed in a
     * different session than the one that set the filter, and Hibernate Search throws
     * rather than let that surprise you.
     *
     * <p>This is the graceful answer to a bulk load that would otherwise fan out: pause,
     * import, resume, then mass index. The feature is marked incubating upstream.
     */
    public void pauseIndexing() {
        Search.mapping(entityManagerFactory).indexingPlanFilter(ctx -> ctx.exclude(Object.class));
        log.warn("Listener-triggered indexing is PAUSED application-wide. Changes made from now "
                + "on produce no events; a mass index is required to catch up.");
    }

    public void resumeIndexing() {
        Search.mapping(entityManagerFactory).indexingPlanFilter(ctx -> ctx.include(Object.class));
        log.info("Listener-triggered indexing resumed");
    }

    private OutboxPollingSearchMapping outboxPolling() {
        return Search.mapping(entityManagerFactory).extension(OutboxPollingExtension.get());
    }

    /** Age of the oldest pending event, computed in SQL to avoid the JDBC timezone trap. */
    public Duration oldestPendingAge() {
        Long seconds = jdbc.queryForObject(
                "SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(process_after), CURRENT_TIMESTAMP(3)), 0) "
                        + "FROM hsearch_outbox_event", new MapSqlParameterSource(), Long.class);
        return Duration.ofSeconds(seconds == null ? 0 : Math.max(0, seconds));
    }

    /**
     * Processors registered in Hibernate Search's agent table, by state.
     *
     * <p>Hibernate Search ships no metrics of its own — none of its jars contain a
     * Micrometer class — so everything observable about its pipeline has to be read out
     * of the two tables it keeps. This is the "is the processor alive, and how is work
     * divided" signal: one RUNNING agent per instance under dynamic sharding, and a
     * count that does not match the instance count means agents are joining or leaving.
     */
    public Map<String, Long> agentsByState() {
        Map<String, Long> byState = new LinkedHashMap<>();
        jdbc.query("SELECT state, COUNT(*) FROM hsearch_agent GROUP BY state",
                new MapSqlParameterSource(), rs -> {
                    byState.put(rs.getString(1), rs.getLong(2));
                });
        return byState;
    }

    /**
     * Agents whose lease has lapsed. A processor that dies without deregistering leaves
     * its row behind; until another agent reaps it, the cluster believes work is assigned
     * to something that is not running, and the backlog quietly stops draining.
     */
    public long expiredAgents() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hsearch_agent WHERE expiration < CURRENT_TIMESTAMP(6)",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    public long documentCount() {
        return indexAdmin.documentCount(READ_ALIAS);
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("outboxBacklog", backlog());
        status.put("outboxAborted", aborted());
        status.put("oldestPendingSeconds", oldestPendingAge().toSeconds());
        status.put("readAlias", READ_ALIAS);
        status.put("indices", indexAdmin.resolveAllIndices(READ_ALIAS));
        status.put("documents", documentCount());
        status.put("agentsByState", agentsByState());
        status.put("expiredAgents", expiredAgents());
        return status;
    }
}
