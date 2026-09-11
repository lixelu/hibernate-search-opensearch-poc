package com.acme.catalog.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does every kind of write reach every index, and how long does it take?
 *
 * <p>Each case measures the interval between the write returning and the change being
 * visible to a search, and records it in the run report. That number — not the query
 * latency — is the one that decides whether an asynchronous read model is acceptable to
 * the product, so it is measured on every run rather than assumed.
 *
 * <p>The child-table cases matter most. A price lives in {@code product_variant} and a
 * quantity in {@code inventory}, two and three tables away from the document; if the
 * pipeline is going to be silently wrong, it will be wrong there.
 */
class IndexPropagationE2eTest extends AbstractE2eTest {

    static List<String> asyncEngines() {
        return E2eConfig.asyncEngines();
    }

    @ParameterizedTest(name = "create becomes visible — {0}")
    @MethodSource("asyncEngines")
    @DisplayName("a create reaches the index")
    void createPropagates(String engine) {
        Latency latency = new Latency("create → visible", engine);

        for (int sample = 0; sample < E2eConfig.propagationSamples(); sample++) {
            String label = "PROPC" + engine.charAt(0) + sample;
            long id = createProduct(label, new BigDecimal("40.00"), "EU", 5,
                    Map.of("material", "cotton"), List.of("new"));
            double millis = awaitMillis(() -> visible(engine, label, id));

            assertThat(millis).as("create never became visible in %s", engine).isNotNegative();
            latency.record(millis);

            catalog.deleteProduct(id);
            assertThat(awaitMillis(() -> goneFromIndex(engine, label)))
                    .as("clean-up delete never left the %s index", engine).isNotNegative();
        }
        E2eReport.latency("Propagation latency", latency);
    }

    @ParameterizedTest(name = "variant reprice becomes visible — {0}")
    @MethodSource("asyncEngines")
    @DisplayName("a write two tables from the document reaches the index")
    void repricePropagates(String engine) {
        Latency latency = new Latency("variant reprice → visible", engine);

        for (int sample = 0; sample < E2eConfig.propagationSamples(); sample++) {
            String label = "PROPR" + engine.charAt(0) + sample;
            long id = createProduct(label, new BigDecimal("800.00"), "EU", 5, Map.of(), List.of());
            assertThat(awaitMillis(() -> visible(engine, label, id))).isNotNegative();

            long variantId = catalog.productById(id).variantIds().get(0);
            catalog.repriceVariant(id, variantId, new BigDecimal("12.00"));

            double millis = awaitMillis(() -> catalog.search(CatalogClient.query()
                    .text(marker(label)).status("ACTIVE").priceBetween("10", "14").page(0, 5), engine)
                    .ids().equals(List.of(id)));

            assertThat(millis).as("reprice never reached the %s index", engine).isNotNegative();
            // and it must have left the window it used to be in
            assertThat(catalog.search(CatalogClient.query()
                    .text(marker(label)).status("ACTIVE").priceBetween("700", "900").page(0, 5), engine).ids())
                    .as("the old price is still indexed in %s", engine).isEmpty();
            latency.record(millis);

            catalog.deleteProduct(id);
            assertThat(awaitMillis(() -> goneFromIndex(engine, label))).isNotNegative();
        }
        E2eReport.latency("Propagation latency", latency);
    }

    @ParameterizedTest(name = "stock change becomes visible — {0}")
    @MethodSource("asyncEngines")
    @DisplayName("a write three tables from the document reaches the index")
    void stockChangePropagates(String engine) {
        Latency latency = new Latency("stock change → visible", engine);

        for (int sample = 0; sample < E2eConfig.propagationSamples(); sample++) {
            String label = "PROPS" + engine.charAt(0) + sample;
            long id = createProduct(label, new BigDecimal("50.00"), "APAC", 8, Map.of(), List.of());
            assertThat(awaitMillis(() -> catalog.search(CatalogClient.query()
                    .text(marker(label)).status("ACTIVE").inStockRegion("APAC").page(0, 5), engine)
                    .ids().equals(List.of(id)))).isNotNegative();

            long variantId = catalog.productById(id).variantIds().get(0);
            catalog.adjustStock(id, variantId, "APAC-WH1", 0);

            // The product still exists, so an empty page here really is the index having
            // learned that the region is out of stock, not hydration dropping a dead id.
            double millis = awaitMillis(() -> catalog.search(CatalogClient.query()
                    .text(marker(label)).status("ACTIVE").inStockRegion("APAC").page(0, 5), engine)
                    .ids().isEmpty());

            assertThat(millis).as("stock going to zero never reached the %s index", engine).isNotNegative();
            assertThat(visible(engine, label, id))
                    .as("the product itself should still be indexed in %s", engine).isTrue();
            latency.record(millis);

            catalog.deleteProduct(id);
            assertThat(awaitMillis(() -> goneFromIndex(engine, label))).isNotNegative();
        }
        E2eReport.latency("Propagation latency", latency);
    }

    @ParameterizedTest(name = "delete becomes visible — {0}")
    @MethodSource("asyncEngines")
    @DisplayName("a delete removes the document")
    void deletePropagates(String engine) {
        Latency latency = new Latency("delete → gone from index", engine);

        for (int sample = 0; sample < E2eConfig.propagationSamples(); sample++) {
            String label = "PROPD" + engine.charAt(0) + sample;
            long id = createProduct(label, new BigDecimal("20.00"), "EU", 2, Map.of(), List.of());
            assertThat(awaitMillis(() -> visible(engine, label, id))).isNotNegative();

            catalog.deleteProduct(id);
            double millis = awaitMillis(() -> goneFromIndex(engine, label));

            assertThat(millis).as("delete never reached the %s index", engine).isNotNegative();
            latency.record(millis);
        }
        E2eReport.latency("Propagation latency", latency);
    }
}
