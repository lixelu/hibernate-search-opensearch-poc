package com.acme.catalog.e2e;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Where the suite points and how hard it pushes, all from system properties so the same
 * tests run against a laptop, a CI stack or a deployed environment without a rebuild.
 *
 * <pre>
 *   mvn verify -Pe2e
 *   mvn verify -Pe2e -De2e.baseUrl=https://catalog.staging.internal
 *   mvn verify -Pe2e -De2e.engines=MYSQL,OPENSEARCH -De2e.iterations=50
 * </pre>
 */
public final class E2eConfig {

    private E2eConfig() {
    }

    public static String baseUrl() {
        return System.getProperty("e2e.baseUrl", "http://localhost:8080").replaceAll("/+$", "");
    }

    /** Read paths under test. Every parity and latency test runs once per entry. */
    public static List<String> engines() {
        return Arrays.stream(System.getProperty("e2e.engines", "MYSQL,OPENSEARCH,HIBERNATE_SEARCH")
                .split(",")).map(String::trim).filter(e -> !e.isEmpty()).toList();
    }

    /** Engines backed by an asynchronous index, so a write has to be waited for. */
    public static List<String> asyncEngines() {
        return engines().stream().filter(engine -> !"MYSQL".equals(engine)).toList();
    }

    public static int iterations() {
        return Integer.getInteger("e2e.iterations", 20);
    }

    public static int warmup() {
        return Integer.getInteger("e2e.warmup", 5);
    }

    /** How many times each propagation case is repeated, so the figure has a distribution. */
    public static int propagationSamples() {
        return Integer.getInteger("e2e.propagationSamples", 3);
    }

    /** How long a change may take to appear in an index before the test fails. */
    public static Duration propagationTimeout() {
        return Duration.ofSeconds(Long.getLong("e2e.propagationTimeoutSeconds", 30L));
    }

    public static Duration httpTimeout() {
        return Duration.ofSeconds(Long.getLong("e2e.httpTimeoutSeconds", 120L));
    }

    /** Fixture size. Small by default so the suite stays fast; raise it to stress paging. */
    public static int fixtureProducts() {
        return Integer.getInteger("e2e.fixtureProducts", 40);
    }
}
