package com.acme.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything that decides "which engine answers this GET" lives here, so the
 * migration is a config change and the rollback is a config change.
 */
@ConfigurationProperties("catalog.search")
public record SearchProperties(
        @DefaultValue("MYSQL") Engine engine,
        /** Which engine SHADOW mode compares MySQL against. */
        @DefaultValue("OPENSEARCH") Engine shadowCandidate,
        /** On any OpenSearch error or timeout, silently answer from MySQL instead. */
        @DefaultValue("true") boolean fallbackToMysql,
        @DefaultValue("100") int maxPageSize,
        /**
         * Extra ids requested from the engine beyond the page size, so that rows the
         * index still lists but MySQL no longer has can be replaced instead of leaving
         * a short page. Set to 0 to disable and see the raw drop rate.
         */
        @DefaultValue("5") int hydrationSurplus,
        /**
         * Upper bound on the refill retry, as a multiple of the page size. A page of 20
         * will widen its search window to at most 80 ids before giving up and returning
         * short -- at which point the index is so far behind that the lag alert is the
         * real problem.
         */
        @DefaultValue("4") int maxRefillFactor,
        @DefaultValue OpenSearch opensearch) {

    public enum Engine {
        /** Baseline: joins in MySQL. */
        MYSQL,
        /** OpenSearch answers via the hand-rolled pipeline; MySQL hydrates by primary key. */
        OPENSEARCH,
        /** OpenSearch answers via the index Hibernate Search maintains; MySQL hydrates. */
        HIBERNATE_SEARCH,
        /**
         * Serve MySQL, run OpenSearch alongside, compare the id lists off the
         * response path and emit a mismatch metric. The only honest way to earn
         * confidence before flipping a customer-facing endpoint.
         */
        SHADOW
    }

    public record OpenSearch(
            @DefaultValue("http://localhost:9200") String uri,
            String username,
            String password,
            /** Index names are versioned: products-v1, products-v2, ... never queried directly. */
            @DefaultValue("products") String indexPrefix,
            @DefaultValue("products_read") String readAlias,
            @DefaultValue("products_write") String writeAlias,
            /** Deep paging guard; beyond this, callers must switch to search_after. */
            @DefaultValue("10000") int maxFrom,
            /** Stop counting past this, unless exactTotalHits is set. */
            @DefaultValue("10000") long trackTotalHitsUpTo,
            /**
             * Count every match instead of stopping at the cap. Costs the early
             * termination optimisation: the query must visit every matching document
             * rather than stopping once it has enough for the page. Measure it on your
             * own hit distributions before assuming it is too expensive -- MySQL was
             * paying for an exact COUNT(*) over the joins on every request anyway.
             */
            @DefaultValue("false") boolean exactTotalHits,
            @DefaultValue("1000") int connectTimeoutMillis,
            @DefaultValue("2000") int socketTimeoutMillis,
            @DefaultValue("true") boolean bootstrapIndex) {
    }
}
