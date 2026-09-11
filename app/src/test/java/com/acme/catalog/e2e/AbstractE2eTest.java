package com.acme.catalog.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared base for the end-to-end suite.
 *
 * <p>These tests drive a <em>running</em> service over HTTP rather than starting a Spring
 * context, so they exercise the deployed configuration — the engine that is actually
 * selected, the real relay interval, the real cluster. Start the stack first
 * ({@code make up && make run}) or point the suite elsewhere with
 * {@code -De2e.baseUrl=…}.
 *
 * <p>Every test tags its data with a unique run id, so concurrent runs and leftover rows
 * from earlier runs cannot make an assertion lie.
 */
@Tag("e2e")
public abstract class AbstractE2eTest {

    protected static final CatalogClient catalog = new CatalogClient(E2eConfig.baseUrl());
    protected static final String RUN = "E2E" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();

    @BeforeAll
    static void requireRunningService() {
        if (!catalog.healthy()) {
            fail("""
                    No healthy service at %s.

                    The end-to-end suite drives a running deployment; it does not start one.
                      make up && make run
                    or point it somewhere else:
                      mvn verify -Pe2e -De2e.baseUrl=https://host
                    """.formatted(E2eConfig.baseUrl()));
        }
    }

    /** A sku unique to this run and this case, so nothing collides. */
    protected static String sku(String label) {
        return RUN + "-" + label;
    }

    /**
     * A name containing a token that appears nowhere else, so a full-text query can
     * isolate exactly one product regardless of what else is in the index.
     */
    protected static String marker(String label) {
        return RUN + label;
    }

    protected static long createProduct(String label, BigDecimal price, String region, int quantity,
                                        Map<String, String> attributes, List<String> tags) {
        return catalog.createProduct(sku(label), marker(label) + " cotton Shirt", "BrandE2E",
                "ACTIVE", price, region, quantity, attributes, tags);
    }

    /**
     * Waits for a condition and returns how long it took.
     *
     * @return elapsed milliseconds, or -1 if the condition never held
     */
    protected static double awaitMillis(BooleanSupplier condition) {
        Instant startedAt = Instant.now();
        Duration timeout = E2eConfig.propagationTimeout();
        while (Duration.between(startedAt, Instant.now()).compareTo(timeout) < 0) {
            if (condition.getAsBoolean()) {
                return Duration.between(startedAt, Instant.now()).toNanos() / 1_000_000d;
            }
            sleep(50);
        }
        return -1;
    }

    protected static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True once a caller searching for the marker gets exactly this product back. */
    protected static boolean visible(String engine, String label, long productId) {
        return catalog.search(CatalogClient.query().text(marker(label)).status("ACTIVE").page(0, 5), engine)
                .ids().equals(List.of(productId));
    }

    /**
     * True once the document has actually left the index.
     *
     * <p>An empty result is not sufficient evidence. After a delete the document can still
     * be in the index while hydration silently drops it, because the row it names is gone
     * from MySQL — so a test that only checks for an empty page passes even when deletes
     * never propagate at all. The page reports how many ids it dropped for exactly this
     * reason, so require that to be zero as well.
     */
    protected static boolean goneFromIndex(String engine, String label) {
        CatalogClient.Page page = catalog.search(
                CatalogClient.query().text(marker(label)).status("ACTIVE").page(0, 5), engine);
        return page.ids().isEmpty() && page.staleDropped() == 0;
    }
}
