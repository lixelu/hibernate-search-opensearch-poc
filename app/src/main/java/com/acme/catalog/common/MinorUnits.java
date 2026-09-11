package com.acme.catalog.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One definition of "money as an integer", shared by both indexing pipelines.
 * <p>
 * Money crosses into any index as an exact count of minor units, never as a
 * floating-point value and never as OpenSearch's {@code scaled_float}, whose
 * descending sort can silently return non-maximal documents (spec section 9.1).
 * Hibernate Search maps {@code BigDecimal} to {@code scaled_float} by default, so
 * that mapping is overridden by exposing these values instead.
 */
public final class MinorUnits {

    private MinorUnits() {
    }

    public static long of(BigDecimal amount) {
        return amount == null ? 0L : amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
