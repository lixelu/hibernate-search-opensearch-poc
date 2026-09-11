package com.acme.catalog.ops;

import com.acme.catalog.query.ProductQuery;
import com.acme.catalog.query.ProductSearchPort;
import com.acme.catalog.query.SearchSlice;
import com.acme.catalog.query.SortDirection;
import com.acme.catalog.query.SortField;
import com.acme.catalog.query.hibernatesearch.HibernateSearchProductAdapter;
import com.acme.catalog.query.mysql.MySqlProductSearchAdapter;
import com.acme.catalog.query.opensearch.OpenSearchProductSearchAdapter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the same {@link ProductQuery} through both adapters and reports the
 * distribution, plus whether they agreed on the result.
 * <p>
 * Timings cover the engine call only, not hydration -- hydration is identical for
 * both paths, so including it would flatter OpenSearch by diluting the difference.
 */
@Service
public class BenchmarkService {

    private final MySqlProductSearchAdapter mysql;
    private final List<ProductSearchPort> candidates;

    public BenchmarkService(MySqlProductSearchAdapter mysql,
                            OpenSearchProductSearchAdapter opensearch,
                            HibernateSearchProductAdapter hibernateSearch) {
        this.mysql = mysql;
        this.candidates = List.of(opensearch, hibernateSearch);
    }

    public record EngineTiming(String engine, long minMillis, long p50Millis, long p95Millis, long maxMillis,
                               long totalHits, int returned, String error) {
    }

    /**
     * @param agreesWithMysql whether this candidate returned the same ids, in the same
     *                        order, as the baseline -- the property that has to hold
     *                        before any cutover
     */
    public record CandidateResult(String engine, EngineTiming timing, String speedup,
                                  boolean agreesWithMysql, boolean sameTotal) {
    }

    public record ScenarioResult(String scenario, EngineTiming mysql, List<CandidateResult> candidates) {
    }

    public record BenchmarkReport(int iterations, List<ScenarioResult> scenarios) {
    }

    public Map<String, ProductQuery> scenarios() {
        Map<String, ProductQuery> scenarios = new LinkedHashMap<>();
        scenarios.put("status-page-by-date", query(builder -> builder
                .statuses(List.of("ACTIVE")).sort(SortField.CREATED_AT).direction(SortDirection.DESC)));
        scenarios.put("vendor-country-sort-by-price", query(builder -> builder
                .statuses(List.of("ACTIVE")).vendorCountry("DE")
                .sort(SortField.PRICE).direction(SortDirection.ASC)));
        scenarios.put("category-tags-instock", query(builder -> builder
                .statuses(List.of("ACTIVE")).categoryPathPrefix("/apparel")
                .tags(List.of("sale", "new")).inStockRegion("EU")
                .sort(SortField.RATING).direction(SortDirection.DESC)));
        scenarios.put("eav-attributes-and-price-window", query(builder -> builder
                .statuses(List.of("ACTIVE"))
                .attributes(Map.of("material", "cotton", "fit", "slim"))
                .priceMin(new BigDecimal("20")).priceMax(new BigDecimal("80"))
                .sort(SortField.PRICE).direction(SortDirection.ASC)));
        scenarios.put("fulltext-plus-filters", query(builder -> builder
                .text("shirt").statuses(List.of("ACTIVE")).vendorTier("GOLD")
                .sort(SortField.RELEVANCE).direction(SortDirection.DESC)));
        scenarios.put("everything-at-once", query(builder -> builder
                .text("cotton").statuses(List.of("ACTIVE")).vendorCountry("DE").vendorTier("GOLD")
                .categoryPathPrefix("/apparel").tags(List.of("sale"))
                .attributes(Map.of("material", "cotton"))
                .priceMin(new BigDecimal("15")).priceMax(new BigDecimal("120"))
                .inStockRegion("EU").ratingMin(new BigDecimal("3.0"))
                .sort(SortField.PRICE).direction(SortDirection.ASC)));
        scenarios.put("deep-page-100", query(builder -> builder
                .statuses(List.of("ACTIVE")).sort(SortField.RATING).direction(SortDirection.DESC).page(100)));
        return scenarios;
    }

    public BenchmarkReport run(int iterations, List<String> only) {
        List<ScenarioResult> results = new ArrayList<>();
        scenarios().forEach((name, query) -> {
            if (only != null && !only.isEmpty() && !only.contains(name)) {
                return;
            }
            EngineTiming mysqlTiming = measure(mysql, query, iterations);
            SearchSlice mysqlSlice = safeSearch(mysql, query);

            List<CandidateResult> candidateResults = new ArrayList<>();
            for (ProductSearchPort candidate : candidates) {
                EngineTiming timing = measure(candidate, query, iterations);
                SearchSlice slice = safeSearch(candidate, query);
                boolean sameIds = mysqlSlice != null && slice != null && mysqlSlice.ids().equals(slice.ids());
                boolean sameTotal = mysqlSlice != null && slice != null
                        && mysqlSlice.totalHits() == slice.totalHits();
                String speedup = timing.p50Millis() <= 0
                        ? "n/a"
                        : String.format("%.1fx", mysqlTiming.p50Millis() / (double) Math.max(1, timing.p50Millis()));
                candidateResults.add(new CandidateResult(candidate.engine(), timing, speedup, sameIds, sameTotal));
            }
            results.add(new ScenarioResult(name, mysqlTiming, candidateResults));
        });
        return new BenchmarkReport(iterations, results);
    }

    private EngineTiming measure(ProductSearchPort port, ProductQuery query, int iterations) {
        try {
            // Warm-up: JIT, connection pool, page cache, OpenSearch query cache. Skipping
            // this is the most common way to produce a benchmark nobody believes.
            for (int i = 0; i < 3; i++) {
                port.search(query);
            }
            long[] samples = new long[iterations];
            SearchSlice last = null;
            for (int i = 0; i < iterations; i++) {
                long startedAt = System.nanoTime();
                last = port.search(query);
                samples[i] = (System.nanoTime() - startedAt) / 1_000_000;
            }
            java.util.Arrays.sort(samples);
            return new EngineTiming(port.engine(),
                    samples[0],
                    samples[samples.length / 2],
                    samples[(int) Math.min(samples.length - 1, Math.ceil(samples.length * 0.95) - 1)],
                    samples[samples.length - 1],
                    last == null ? 0 : last.totalHits(),
                    last == null ? 0 : last.ids().size(),
                    null);
        } catch (RuntimeException e) {
            return new EngineTiming(port.engine(), -1, -1, -1, -1, -1, 0, e.toString());
        }
    }

    private SearchSlice safeSearch(ProductSearchPort port, ProductQuery query) {
        try {
            return port.search(query);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ProductQuery query(java.util.function.Consumer<Builder> customiser) {
        Builder builder = new Builder();
        customiser.accept(builder);
        return builder.build();
    }

    /** Tiny mutable builder so the scenario list above stays readable. */
    private static final class Builder {
        private String text;
        private List<String> statuses = List.of();
        private String vendorCountry;
        private String vendorTier;
        private String categoryPathPrefix;
        private List<String> brands = List.of();
        private List<String> tags = List.of();
        private Map<String, String> attributes = Map.of();
        private BigDecimal priceMin;
        private BigDecimal priceMax;
        private String inStockRegion;
        private BigDecimal ratingMin;
        private SortField sort = SortField.CREATED_AT;
        private SortDirection direction = SortDirection.DESC;
        private int page = 0;
        private final int size = 20;

        Builder text(String value) {
            this.text = value;
            return this;
        }

        Builder statuses(List<String> value) {
            this.statuses = value;
            return this;
        }

        Builder vendorCountry(String value) {
            this.vendorCountry = value;
            return this;
        }

        Builder vendorTier(String value) {
            this.vendorTier = value;
            return this;
        }

        Builder categoryPathPrefix(String value) {
            this.categoryPathPrefix = value;
            return this;
        }

        Builder tags(List<String> value) {
            this.tags = value;
            return this;
        }

        Builder attributes(Map<String, String> value) {
            this.attributes = value;
            return this;
        }

        Builder priceMin(BigDecimal value) {
            this.priceMin = value;
            return this;
        }

        Builder priceMax(BigDecimal value) {
            this.priceMax = value;
            return this;
        }

        Builder inStockRegion(String value) {
            this.inStockRegion = value;
            return this;
        }

        Builder ratingMin(BigDecimal value) {
            this.ratingMin = value;
            return this;
        }

        Builder sort(SortField value) {
            this.sort = value;
            return this;
        }

        Builder direction(SortDirection value) {
            this.direction = value;
            return this;
        }

        Builder page(int value) {
            this.page = value;
            return this;
        }

        ProductQuery build() {
            return new ProductQuery(text, statuses, vendorCountry, vendorTier, categoryPathPrefix, brands, tags,
                    attributes, priceMin, priceMax, inStockRegion, ratingMin, sort, direction, page, size, null);
        }
    }
}
