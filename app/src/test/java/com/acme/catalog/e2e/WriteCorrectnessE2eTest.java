package com.acme.catalog.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does the write actually do what it said?
 *
 * <p>These assertions read back through the point-lookup endpoint, which never touches an
 * index — so a failure here is a write bug, not an indexing bug. Keeping that separation
 * is what makes the propagation suite interpretable: if writes are correct and the index
 * disagrees, the pipeline is at fault, and vice versa.
 */
class WriteCorrectnessE2eTest extends AbstractE2eTest {

    @Test
    @DisplayName("a created product is readable by id with everything it was given")
    void createPersistsTheWholeAggregate() {
        long id = createProduct("CREATE", new BigDecimal("42.50"), "EU", 7,
                Map.of("material", "cotton", "fit", "slim"), List.of("new", "sale"));

        CatalogClient.Product product = catalog.productById(id);
        assertThat(product).isNotNull();
        assertThat(product.sku()).isEqualTo(sku("CREATE"));
        assertThat(product.status()).isEqualTo("ACTIVE");
        assertThat(product.variantIds()).hasSize(1);
        assertThat(product.minPrice()).isEqualByComparingTo("42.50");
        assertThat(product.inStockRegions()).containsExactly("EU");

        catalog.deleteProduct(id);
    }

    @Test
    @DisplayName("an update changes only what it names")
    void updateIsNarrow() {
        long id = createProduct("UPDATE", new BigDecimal("30.00"), "EU", 3,
                Map.of("material", "wool"), List.of("new"));

        catalog.updateProduct(id, Map.of("status", "ARCHIVED"));

        CatalogClient.Product product = catalog.productById(id);
        assertThat(product.status()).isEqualTo("ARCHIVED");
        // untouched by a status change
        assertThat(product.minPrice()).isEqualByComparingTo("30.00");
        assertThat(product.inStockRegions()).containsExactly("EU");

        catalog.deleteProduct(id);
    }

    @Test
    @DisplayName("repricing a variant changes the price and nothing else")
    void repriceChangesTheChildRow() {
        long id = createProduct("REPRICE", new BigDecimal("99.00"), "US", 4, Map.of(), List.of());
        long variantId = catalog.productById(id).variantIds().get(0);

        catalog.repriceVariant(id, variantId, new BigDecimal("11.25"));

        CatalogClient.Product product = catalog.productById(id);
        assertThat(product.minPrice()).isEqualByComparingTo("11.25");
        assertThat(product.status()).isEqualTo("ACTIVE");
        assertThat(product.inStockRegions()).containsExactly("US");

        catalog.deleteProduct(id);
    }

    @Test
    @DisplayName("a stock adjustment to zero takes the region out of stock")
    void stockAdjustmentEmptiesTheRegion() {
        long id = createProduct("STOCK", new BigDecimal("25.00"), "APAC", 9, Map.of(), List.of());
        long variantId = catalog.productById(id).variantIds().get(0);

        catalog.adjustStock(id, variantId, "APAC-WH1", 0);

        assertThat(catalog.productById(id).inStockRegions()).isEmpty();

        catalog.deleteProduct(id);
    }

    @Test
    @DisplayName("a deleted product is gone, and asking for it again is a 404")
    void deleteRemovesTheAggregate() {
        long id = createProduct("DELETE", new BigDecimal("15.00"), "EU", 1, Map.of(), List.of());
        assertThat(catalog.productById(id)).isNotNull();

        catalog.deleteProduct(id);

        assertThat(catalog.productById(id)).isNull();
    }
}
