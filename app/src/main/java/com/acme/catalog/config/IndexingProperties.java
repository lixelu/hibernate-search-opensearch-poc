package com.acme.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("catalog.indexing")
public record IndexingProperties(
        /** Turn the relay off on nodes that should not index (e.g. read-only replicas of the app). */
        @DefaultValue("true") boolean relayEnabled,
        /**
         * Kill switch for the write-path hook. Setting this to false stops recording
         * outbox rows: the index goes stale, but POST/PUT lose their last tie to the
         * search stack. Also the A/B switch for measuring what that tie costs.
         */
        @DefaultValue("true") boolean outboxEnabled,
        /** Outbox rows claimed per poll. Bigger batches amortise the bulk request. */
        @DefaultValue("500") int claimBatchSize,
        /** Plain milliseconds: @Scheduled(fixedDelayString) does not parse "200ms". */
        @DefaultValue("200") long pollDelayMs,
        /** A claim older than this is considered abandoned (worker crashed) and re-claimed. */
        @DefaultValue("60s") Duration claimTimeout,
        /** Documents per _bulk request. */
        @DefaultValue("500") int bulkSize,
        /** Rows per page during a full backfill. */
        @DefaultValue("2000") int reindexPageSize,
        @DefaultValue("5") int maxAttempts,
        /**
         * Largest fan-out a single parent change may perform inline. Beyond this the
         * write is refused and the operator is told to reindex out of band, because a
         * top-level category in a real catalogue is millions of documents and that is
         * not work to do inside a web request.
         */
        @DefaultValue("10000") int maxParentFanOut,
        /**
         * Where the OpenSearch external document version comes from. See the spec
         * section on version sources for what each one can and cannot order.
         */
        @DefaultValue("AGGREGATE_VERSION") VersionSource versionSource) {

    public enum VersionSource {
        /**
         * A monotonic counter on the aggregate root, bumped by every write including
         * writes to child tables. Orders everything. Needs a column and one line of
         * discipline per write method.
         */
        AGGREGATE_VERSION,
        /**
         * {@code GREATEST(updated_at)} across the tables of the aggregate that have
         * one, as epoch milliseconds. Needs no new column and no write-path change,
         * and unlike the counter it also catches writes that bypass the application.
         *
         * <p><b>It is not monotonic under child-row deletes.</b> Deleting the most
         * recently updated child row lowers {@code MAX(updated_at)}, so the corrected
         * document carries a LOWER version than the one already indexed, and
         * {@code external_gte} rejects it — permanently, and while the relay reports
         * success. Measured: deleting one inventory row moved the version back 1.8
         * seconds and left the index advertising stock that no longer exists.
         *
         * <p>Only choose this where child rows are soft-deleted (a soft delete is an
         * UPDATE, so the timestamp moves forward), or where child rows are never
         * deleted. Otherwise see the spec for the binlog-coordinate and
         * serialise-per-key alternatives.
         */
        MAX_UPDATED_AT,
        /**
         * The database clock at the instant the relay read the aggregate, in epoch
         * microseconds, taken from {@code CURRENT_TIMESTAMP(6)} inside the same query
         * that loads the state.
         *
         * <p>This is the version source to reach for when a counter column is not
         * available. It works because of one property: under READ COMMITTED, a read
         * that happens later sees a superset of the transactions an earlier read saw.
         * So a higher read timestamp always means a state at least as new — which is
         * exactly the invariant {@code external_gte} needs, and the invariant an
         * <em>event</em> timestamp does not have.
         *
         * <p>Unlike {@link #MAX_UPDATED_AT} it does not read the data at all, so child
         * row deletes cannot move it backwards. It requires: every relay reading from
         * the same clock (take it from the database, never from the application
         * server), reads served by the primary rather than a lagging replica, and a
         * clock that does not step backwards.
         */
        READ_TIMESTAMP
    }
}
