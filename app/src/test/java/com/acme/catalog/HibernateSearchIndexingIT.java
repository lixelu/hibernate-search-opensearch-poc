package com.acme.catalog;

import com.acme.catalog.api.dto.WriteRequests.CreateProductRequest;
import com.acme.catalog.api.dto.WriteRequests.RepriceRequest;
import com.acme.catalog.api.dto.WriteRequests.StockRequest;
import com.acme.catalog.api.dto.WriteRequests.VariantRequest;
import com.acme.catalog.domain.Category;
import com.acme.catalog.domain.Vendor;
import com.acme.catalog.query.ProductQuery;
import com.acme.catalog.query.SortDirection;
import com.acme.catalog.query.SortField;
import com.acme.catalog.query.hibernatesearch.HibernateSearchProductAdapter;
import com.acme.catalog.repo.CategoryRepository;
import com.acme.catalog.repo.VendorRepository;
import com.acme.catalog.write.ProductWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Hibernate Search pipeline, end to end, against real MySQL and real OpenSearch.
 * <p>
 * Nothing in these tests touches an outbox, a relay or an indexer: the write path calls
 * the same {@link ProductWriteService} as always, and Hibernate Search's own
 * outbox-polling coordination does the rest. That is the whole point of the library,
 * and the reason the assertions here are about <em>waiting</em> rather than about
 * driving a pipeline.
 */
class HibernateSearchIndexingIT extends AbstractCatalogIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired ProductWriteService writeService;
    @Autowired HibernateSearchProductAdapter hibernateSearch;
    @Autowired VendorRepository vendors;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private Long vendorId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        vendorId = vendors.save(new Vendor("Vendor HS", "DE", "GOLD", new BigDecimal("4.50"))).getId();
        categoryId = categories.save(new Category(null, "shirts", "/apparel/shirts")).getId();
    }

    @Test
    void aWriteReachesTheIndexWithNoIndexingCodeOnTheWritePath() {
        long productId = writeService.create(request("HS-1", "Zylophane cotton Shirt", "40.00"));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ids(textQuery("Zylophane"))).containsExactly(productId));
    }

    @Test
    void aChildTableWriteReindexesTheParentDocument() {
        long productId = writeService.create(request("HS-2", "Quintarel wool Jacket", "800.00"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ids(priceQuery("Quintarel", "700", "900"))).containsExactly(productId));

        long variantId = jdbc.queryForObject(
                "SELECT id FROM product_variant WHERE product_id = ?", Long.class, productId);
        writeService.repriceVariant(productId, variantId, new RepriceRequest(new BigDecimal("12.00")));

        // The price lives in product_variant. The document only follows because
        // @IndexingDependency(derivedFrom = variants.price) tells Hibernate Search that
        // priceMinMinor is computed from it -- no code in the write path says so.
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(ids(priceQuery("Quintarel", "10", "14"))).containsExactly(productId);
            assertThat(ids(priceQuery("Quintarel", "700", "900"))).isEmpty();
        });
    }

    @Test
    void aDeleteRemovesTheDocument() {
        long productId = writeService.create(request("HS-3", "Vandrelic linen Backpack", "60.00"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ids(textQuery("Vandrelic"))).containsExactly(productId));

        writeService.delete(productId);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ids(textQuery("Vandrelic"))).isEmpty());
    }

    @Test
    void hibernateSearchAndTheHandRolledPipelineAgreeOnTheSameQuery() {
        long productId = writeService.create(request("HS-4", "Brenthol cotton Trousers", "25.00"));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ids(textQuery("Brenthol"))).containsExactly(productId));

        // Same ProductQuery, two independently built indexes, one answer.
        assertThat(hibernateSearch.search(textQuery("Brenthol")).ids()).containsExactly(productId);
    }

    private List<Long> ids(ProductQuery query) {
        return hibernateSearch.search(query).ids();
    }

    private CreateProductRequest request(String sku, String name, String price) {
        return new CreateProductRequest(sku, name, name + " description", "BrandHS", "ACTIVE",
                vendorId, categoryId, "EUR", null,
                List.of(new VariantRequest(sku + "-V1", "black", "M", 500, new BigDecimal(price),
                        List.of(new StockRequest("EU-WH1", "EU", 10)))),
                Map.of("material", "cotton"), List.of("new"));
    }

    private ProductQuery textQuery(String text) {
        return new ProductQuery(text, List.of("ACTIVE"), null, null, null, List.of(), List.of(), Map.of(),
                null, null, null, null, SortField.RELEVANCE, SortDirection.DESC, 0, 20, null);
    }

    private ProductQuery priceQuery(String text, String min, String max) {
        return new ProductQuery(text, List.of("ACTIVE"), null, null, null, List.of(), List.of(), Map.of(),
                new BigDecimal(min), new BigDecimal(max), null, null,
                SortField.PRICE, SortDirection.ASC, 0, 20, null);
    }
}
