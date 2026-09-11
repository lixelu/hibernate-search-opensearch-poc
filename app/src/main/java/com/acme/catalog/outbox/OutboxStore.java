package com.acme.catalog.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Repository
public class OutboxStore {

    public static final String AGGREGATE_PRODUCT = "product";

    private final NamedParameterJdbcTemplate jdbc;
    private final MeterRegistry meters;

    public OutboxStore(NamedParameterJdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    public record ClaimedEvent(long eventId, long aggregateId, Instant occurredAt) {
    }

    /**
     * MANDATORY, not REQUIRED: recording an outbox row outside the caller's
     * transaction would silently reintroduce the dual-write problem this pattern
     * exists to remove. Better a loud failure at the first integration test.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, long aggregateId) {
        jdbc.update("INSERT INTO outbox_event (aggregate_type, aggregate_id) VALUES (:type, :id)",
                new MapSqlParameterSource().addValue("type", aggregateType).addValue("id", aggregateId));
        // Not ".created": Prometheus reserves the _created suffix, and Micrometer
        // silently drops it, leaving a metric named nothing like what you configured.
        meters.counter("catalog.outbox.events.written", "pipeline", "hand-rolled").increment();
    }

    /**
     * Claims a batch for this worker. {@code FOR UPDATE ... SKIP LOCKED} lets every
     * application instance poll the same table concurrently without a distributed
     * lock, a leader election, or ShedLock.
     */
    @Transactional
    public List<ClaimedEvent> claim(String aggregateType, int batchSize, Duration claimTimeout,
                                    int maxAttempts, String worker) {
        Timestamp staleBefore = Timestamp.from(Instant.now().minus(claimTimeout));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("type", aggregateType)
                .addValue("staleBefore", staleBefore)
                .addValue("maxAttempts", maxAttempts)
                .addValue("batchSize", batchSize);

        // A row that has failed maxAttempts times stops being claimed. It deliberately
        // stays in the table: the backlog gauge and the lag alert keep firing, and
        // last_error says why, instead of the batch spinning forever and starving the
        // rows behind it.
        List<ClaimedEvent> claimed = jdbc.query("""
                SELECT id, aggregate_id, occurred_at
                  FROM outbox_event
                 WHERE aggregate_type = :type
                   AND attempts < :maxAttempts
                   AND (claimed_at IS NULL OR claimed_at < :staleBefore)
                 ORDER BY id
                 LIMIT :batchSize
                 FOR UPDATE SKIP LOCKED
                """, params, (rs, n) -> new ClaimedEvent(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant()));

        if (claimed.isEmpty()) {
            return List.of();
        }
        jdbc.update("""
                UPDATE outbox_event
                   SET claimed_at = :now, claimed_by = :worker, attempts = attempts + 1
                 WHERE id IN (:ids)
                """, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("worker", worker)
                .addValue("ids", claimed.stream().map(ClaimedEvent::eventId).toList()));
        return claimed;
    }

    @Transactional
    public void complete(List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM outbox_event WHERE id IN (:ids)", new MapSqlParameterSource("ids", eventIds));
    }

    /** Hands the batch back so the next poll retries it; keeps the failure for triage. */
    @Transactional
    public void releaseWithError(List<Long> eventIds, String error) {
        if (eventIds.isEmpty()) {
            return;
        }
        jdbc.update("""
                UPDATE outbox_event
                   SET claimed_at = NULL, claimed_by = NULL, last_error = :error
                 WHERE id IN (:ids)
                """, new MapSqlParameterSource()
                .addValue("ids", eventIds)
                .addValue("error", error == null ? null : error.substring(0, Math.min(error.length(), 500))));
    }

    /** Denominator for the "does the index hold everything?" gauge. */
    public long productRowCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM product", new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    public long backlog() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Age of the oldest unprocessed change: the number to alert on.
     * <p>
     * Computed by the database rather than by subtracting a JDBC {@code Timestamp}
     * from {@code Instant.now()}. {@code getTimestamp()} reinterprets the stored value
     * in the JVM's default zone, so a UTC database read from a UTC+3 JVM reports a lag
     * three hours off -- silently, and only in the metric you rely on to tell you the
     * index is healthy.
     */
    public Duration oldestPendingAge() {
        Long seconds = jdbc.queryForObject(
                "SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(occurred_at), CURRENT_TIMESTAMP(3)), 0) "
                        + "FROM outbox_event",
                new MapSqlParameterSource(), Long.class);
        return Duration.ofSeconds(seconds == null ? 0 : seconds);
    }
}
