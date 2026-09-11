package com.acme.catalog.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every read path, over the same set of query shapes, judged two ways: does it return the
 * same answer as MySQL, and how fast is it.
 *
 * <p>Parity is the gate. An engine that is fast and disagrees is not a faster engine, it
 * is a different product — and a divergence found here is exactly the class of bug that
 * shipped a wrong sort order in this project once already.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReadPathE2eTest extends AbstractE2eTest {

    /** Query shapes chosen to exercise a different part of the translation in each case. */
    private static Map<String, CatalogClient.Query> scenarios() {
        Map<String, CatalogClient.Query> scenarios = new LinkedHashMap<>();
        scenarios.put("status + sort by date",
                CatalogClient.query().status("ACTIVE").sort("CREATED_AT", "DESC").page(0, 20));
        scenarios.put("sort by price ascending",
                CatalogClient.query().status("ACTIVE").sort("PRICE", "ASC").page(0, 20));
        scenarios.put("sort by rating descending",
                CatalogClient.query().status("ACTIVE").sort("RATING", "DESC").page(0, 20));
        scenarios.put("vendor country",
                CatalogClient.query().status("ACTIVE").vendorCountry("DE").sort("PRICE", "ASC").page(0, 20));
        scenarios.put("category prefix + tag + stock",
                CatalogClient.query().status("ACTIVE").categoryPath("/apparel").tag("sale")
                        .inStockRegion("EU").sort("RATING", "DESC").page(0, 20));
        scenarios.put("EAV attributes + price window",
                CatalogClient.query().status("ACTIVE").attribute("material", "cotton")
                        .attribute("fit", "slim").priceBetween("20", "80").sort("PRICE", "ASC").page(0, 20));
        scenarios.put("full text + filters",
                CatalogClient.query().text("shirt").status("ACTIVE").sort("RELEVANCE", "DESC").page(0, 20));
        scenarios.put("second page",
                CatalogClient.query().status("ACTIVE").sort("RATING", "DESC").page(1, 20));
        scenarios.put("deep page",
                CatalogClient.query().status("ACTIVE").sort("RATING", "DESC").page(100, 20));
        scenarios.put("matches nothing",
                CatalogClient.query().status("ACTIVE").brand("NoSuchBrandAtAll").page(0, 20));
        return scenarios;
    }

    static List<String> candidates() {
        return E2eConfig.engines().stream().filter(engine -> !"MYSQL".equals(engine)).toList();
    }

    @ParameterizedTest(name = "{0} agrees with MySQL on every scenario")
    @MethodSource("candidates")
    @Order(1)
    @DisplayName("every candidate engine returns the baseline's answer")
    void resultsAgreeWithTheBaseline(String engine) {
        List<String> divergences = new java.util.ArrayList<>();

        scenarios().forEach((name, query) -> {
            CatalogClient.Page baseline = catalog.search(query, "MYSQL");
            CatalogClient.Page candidate = catalog.search(query, engine);

            boolean sameIds = baseline.ids().equals(candidate.ids());
            E2eReport.parity(name, engine, baseline.total(), sameIds);
            if (!sameIds) {
                divergences.add("%s: mysql=%s %s=%s".formatted(name, baseline.ids(), engine, candidate.ids()));
            }
            if (baseline.total() != candidate.total() && !candidate.totalIsLowerBound()) {
                divergences.add("%s: total mysql=%d %s=%d".formatted(
                        name, baseline.total(), engine, candidate.total()));
            }
        });

        assertThat(divergences)
                .as("%s diverged from the MySQL baseline; ordering parity is the cutover gate", engine)
                .isEmpty();
    }

    @ParameterizedTest(name = "latency — {0}")
    @MethodSource("allEngines")
    @Order(2)
    @DisplayName("every read path is measured on every scenario")
    void measureLatency(String engine) {
        scenarios().forEach((name, query) -> {
            for (int i = 0; i < E2eConfig.warmup(); i++) {
                catalog.search(query, engine);
            }
            Latency endToEnd = new Latency(name, engine);
            Latency engineOnly = new Latency(name + " (engine only)", engine);
            for (int i = 0; i < E2eConfig.iterations(); i++) {
                CatalogClient.Page page = endToEnd.time(() -> catalog.search(query, engine));
                engineOnly.record(page.engineMillis());
            }
            E2eReport.latency("Read latency, end to end", endToEnd);
            E2eReport.latency("Read latency, engine only", engineOnly);
        });
    }

    static List<String> allEngines() {
        return E2eConfig.engines();
    }

    @Test
    @Order(3)
    @DisplayName("a page is never short, and pages do not overlap")
    void pagingIsConsistentAcrossEngines() {
        for (String engine : E2eConfig.engines()) {
            CatalogClient.Page first = catalog.search(
                    CatalogClient.query().status("ACTIVE").sort("RATING", "DESC").page(0, 20), engine);
            CatalogClient.Page second = catalog.search(
                    CatalogClient.query().status("ACTIVE").sort("RATING", "DESC").page(1, 20), engine);

            assertThat(first.ids()).as("%s returned a short first page", engine).hasSize(20);
            assertThat(second.ids()).as("%s returned a short second page", engine).hasSize(20);
            assertThat(first.ids()).as("%s repeated rows across pages — the sort has no stable "
                    + "tie-breaker", engine).doesNotContainAnyElementsOf(second.ids());
            assertThat(first.staleDropped())
                    .as("%s dropped stale ids; the index is behind", engine).isZero();
        }
    }

    @Test
    @Order(4)
    @DisplayName("a window past the deep-paging limit is refused, not silently clamped")
    void deepPagingIsRefusedRatherThanClamped() {
        CatalogClient.Page refused = catalog.search(
                CatalogClient.query().status("ACTIVE").page(20_000, 100), "OPENSEARCH");

        assertThat(refused.status())
                .as("a window beyond max-from must be a 400, never a quietly different page")
                .isEqualTo(400);
        E2eReport.note("Deep paging past max-from returns HTTP " + refused.status() + " as intended.");
    }

    @Test
    @Order(5)
    @DisplayName("the pipelines are caught up and agree with MySQL")
    void indexesAreReconciled() {
        var outbox = catalog.outboxStatus();
        var hibernateSearch = catalog.hibernateSearchStatus();
        var reconciliation = catalog.reconcile(200);

        E2eReport.note("Outbox backlog %d, lag %ds. Hibernate Search backlog %d, aborted %d."
                .formatted(outbox.path("backlog").asLong(), outbox.path("oldestPendingSeconds").asLong(),
                        hibernateSearch.path("outboxBacklog").asLong(),
                        hibernateSearch.path("outboxAborted").asLong()));
        E2eReport.note("Reconciliation over %d sampled rows: %d missing, %d stale, %d ahead."
                .formatted(reconciliation.path("sampled").asInt(),
                        reconciliation.path("missing").size(), reconciliation.path("stale").size(),
                        reconciliation.path("ahead").size()));

        assertThat(hibernateSearch.path("outboxAborted").asLong())
                .as("Hibernate Search gave up on some events; they will never be retried").isZero();
        assertThat(reconciliation.path("missing").size())
                .as("rows exist in MySQL with no document").isZero();
        assertThat(reconciliation.path("stale").size())
                .as("documents are behind their rows").isZero();
        assertThat(reconciliation.path("ahead").size())
                .as("documents are AHEAD of their rows — under a timestamp version source "
                        + "this is the signature of a version that went backwards").isZero();
    }
}
