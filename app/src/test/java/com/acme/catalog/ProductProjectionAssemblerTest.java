package com.acme.catalog;

import com.acme.catalog.config.IndexingProperties;
import com.acme.catalog.config.IndexingProperties.VersionSource;
import com.acme.catalog.projection.ProductDocument;
import com.acme.catalog.projection.ProductProjectionAssembler;
import com.acme.catalog.read.ProductAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projection is where SQL semantics and index semantics have to agree. It is a
 * pure function, so that agreement is checkable without a cluster, a database, or a
 * Spring context -- which is the difference between a check that runs on every commit
 * and one that runs never.
 */
class ProductProjectionAssemblerTest {

    private final ProductProjectionAssembler assembler = assemblerUsing(VersionSource.AGGREGATE_VERSION);

    private static ProductProjectionAssembler assemblerUsing(VersionSource source) {
        return new ProductProjectionAssembler(new IndexingProperties(
                true, true, 500, 200L, Duration.ofSeconds(60), 500, 2000, 5, 10_000, source));
    }

    @Test
    void reducesVariantsToAPriceIntervalAndStockRegions() {
        ProductDocument document = assembler.toDocument(aggregate(
                List.of(
                        variant(1, "19.99", stock("EU", 5), stock("US", 0)),
                        variant(2, "49.50", stock("APAC", 12))),
                Map.of("material", "cotton")));

        // These replace the correlated MIN()/MAX() sub-selects in SQL, and they are
        // exact integers of minor units rather than floats.
        assertThat(document.priceMinMinor()).isEqualTo(1999L);
        assertThat(document.priceMaxMinor()).isEqualTo(4950L);
        // Zero-quantity rows must not make a region "in stock", or the two engines
        // will silently disagree on the inStockRegion filter.
        assertThat(document.inStockRegions()).containsExactlyInAnyOrder("EU", "APAC");
        assertThat(document.totalStock()).isEqualTo(17);
        assertThat(document.variantCount()).isEqualTo(2);
    }

    @Test
    void flattensAttributesIntoKeywordPairs() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("material", "cotton");
        attributes.put("fit", "slim");

        ProductDocument document = assembler.toDocument(aggregate(List.of(variant(1, "10.00")), attributes));

        assertThat(document.attrFlat()).containsExactly("material=cotton", "fit=slim");
    }

    @Test
    void carriesTheAggregateVersionAsTheDocumentVersion() {
        ProductDocument document = assembler.toDocument(aggregate(List.of(variant(1, "10.00")), Map.of()));

        assertThat(document.version()).isEqualTo(7L);
        assertThat(document.updatedAt()).endsWith("Z");
    }

    @Test
    void canPublishTheDerivedTimestampVersionInstead() {
        ProductAggregate product = aggregate(List.of(variant(1, "10.00")), Map.of());

        // For a schema that cannot add a counter column: GREATEST(updated_at) across the
        // aggregate, in epoch millis, becomes the external document version instead.
        ProductDocument document = assemblerUsing(VersionSource.MAX_UPDATED_AT).toDocument(product);

        assertThat(document.version()).isEqualTo(1_767_225_845_123L);
        assertThat(document.version()).isNotEqualTo(product.aggregateVersion());
    }

    @Test
    void bothVersionSourcesAreMonotonicForTheSameAggregate() {
        ProductAggregate older = aggregate(List.of(variant(1, "10.00")), Map.of());
        ProductAggregate newer = new ProductAggregate(older.id(), older.sku(), older.name(),
                older.description(), older.brand(), older.status(), older.currency(), older.ratingAvg(),
                older.ratingCount(), older.launchDate(), older.aggregateVersion() + 1,
                older.derivedVersion() + 1, older.createdAt(), older.updatedAt(), older.vendor(),
                older.category(), older.variants(), older.attributes(), older.tags());

        for (VersionSource source : VersionSource.values()) {
            ProductProjectionAssembler assembler = assemblerUsing(source);
            assertThat(assembler.toDocument(newer).version())
                    .as("%s must increase", source)
                    .isGreaterThan(assembler.toDocument(older).version());
        }
    }

    private ProductAggregate aggregate(List<ProductAggregate.VariantView> variants, Map<String, String> attributes) {
        return new ProductAggregate(1L, "SKU-1", "Classic cotton Shirt", "A shirt", "Brand1", "ACTIVE", "EUR",
                new BigDecimal("4.20"), 12, null, 7L, 1_767_225_845_123L, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T03:04:05.123456789Z"),
                new ProductAggregate.VendorRef(3L, "Vendor 3", "DE", "GOLD", new BigDecimal("4.50")),
                new ProductAggregate.CategoryRef(9L, "shirts", "/apparel/shirts"),
                variants, attributes, List.of("sale"));
    }

    private ProductAggregate.VariantView variant(long id, String price, ProductAggregate.StockView... stock) {
        return new ProductAggregate.VariantView(id, "SKU-1-" + id, "black", "M", 500,
                new BigDecimal(price), "ACTIVE", List.of(stock));
    }

    private ProductAggregate.StockView stock(String region, int quantity) {
        return new ProductAggregate.StockView(region + "-WH1", region, quantity, 0);
    }
}
