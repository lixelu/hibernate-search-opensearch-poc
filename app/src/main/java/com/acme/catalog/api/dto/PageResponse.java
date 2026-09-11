package com.acme.catalog.api.dto;

import java.util.List;

/**
 * Timings are part of the contract on purpose: during migration you want every
 * response to say which engine answered and where the milliseconds went.
 */
public record PageResponse<T>(
        List<T> items,
        long total,
        boolean totalIsLowerBound,
        int page,
        int size,
        String engine,
        long engineMillis,
        long hydrationMillis,
        /**
         * Ids the engine returned that no longer exist in MySQL -- deleted, or
         * indexed under a filter they no longer match. Non-zero is normal at low
         * levels and worth an alert if it climbs.
         */
        int staleDropped) {
}
