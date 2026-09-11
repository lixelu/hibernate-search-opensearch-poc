package com.acme.catalog;

import com.acme.catalog.api.dto.WriteRequests.CreateProductRequest;
import com.acme.catalog.api.dto.WriteRequests.RepriceRequest;
import com.acme.catalog.api.dto.WriteRequests.StockRequest;
import com.acme.catalog.api.dto.WriteRequests.VariantRequest;
import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.domain.Category;
import com.acme.catalog.domain.Vendor;
import com.acme.catalog.indexer.IndexAdmin;
import com.acme.catalog.indexer.ProductIndexer;
import com.acme.catalog.outbox.OutboxRelay;
import com.acme.catalog.outbox.OutboxStore;
import com.acme.catalog.projection.ProductDocument;
import com.acme.catalog.query.ProductQuery;
import com.acme.catalog.query.SearchSlice;
import com.acme.catalog.query.SortDirection;
import com.acme.catalog.query.SortField;
import com.acme.catalog.query.mysql.MySqlProductSearchAdapter;
import com.acme.catalog.query.opensearch.OpenSearchProductSearchAdapter;
import com.acme.catalog.repo.CategoryRepository;
import com.acme.catalog.repo.VendorRepository;
import com.acme.catalog.write.ProductWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the integration points, against real MySQL and real OpenSearch.
 * <p>
 * The relay's scheduler is switched off so the test drives {@code drainOnce()}
 * itself: asynchronous pipelines tested with sleeps are how you get a suite that is
 * both slow and flaky.
 */
class OutboxToOpenSearchIT extends AbstractCatalogIT {

    @Autowired ProductWriteService writeService;
    @Autowired OutboxRelay relay;
    @Autowired OutboxStore outbox;
    @Autowired IndexAdmin indexAdmin;
    @Autowired ProductIndexer indexer;
    @Autowired MySqlProductSearchAdapter mysql;
    @Autowired OpenSearchProductSearchAdapter opensearch;
    @Autowired VendorRepository vendors;
    @Autowired CategoryRepository categories;
    @Autowired SearchProperties properties;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Long vendorId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        vendorId = vendors.save(new Vendor("Vendor A", "DE", "GOLD", new BigDecimal("4.50"))).getId();
        categoryId = categories.save(new Category(null, "shirts", "/apparel/shirts")).getId();
    }

    @Test
    void writeGoesToMysqlAndReachesOpenSearchThroughTheOutbox() {
        long backlogBefore = outbox.backlog();

        long productId = writeService.create(request("SKU-IT-1", "Perijove cotton Shirt", "29.99"));

        // The write path itself must not have talked to OpenSearch: all it left behind
        // is exactly one more outbox row.
        assertThat(outbox.backlog()).isEqualTo(backlogBefore + 1);
        assertThat(idsFrom(opensearch, textQuery("Perijove"))).isEmpty();

        drain();

        assertThat(outbox.backlog()).isZero();
        assertThat(idsFrom(opensearch, textQuery("Perijove"))).containsExactly(productId);
        // Same query, same answer, either engine -- the property that makes the
        // rollout switch safe.
        assertThat(idsFrom(opensearch, textQuery("Perijove")))
                .isEqualTo(idsFrom(mysql, textQuery("Perijove")));
    }

    @Test
    void childTableWriteRepricesTheDocument() {
        long productId = writeService.create(request("SKU-IT-2", "Anthelion wool Jacket", "50.00"));
        drain();

        // Scoped by a marker word, so other tests sharing this database cannot drift
        // into the window and make the assertion lie.
        ProductQuery cheap = priceQuery("Anthelion", new BigDecimal("10"), new BigDecimal("20"));
        assertThat(idsFrom(opensearch, cheap)).isEmpty();

        long variantId = variantIdOf(productId);
        writeService.repriceVariant(productId, variantId, new RepriceRequest(new BigDecimal("15.00")));
        drain();

        // A price lives in product_variant, not in product; the document only follows
        // because the write path bumps the parent aggregate version.
        assertThat(idsFrom(opensearch, cheap)).containsExactly(productId);
        assertThat(idsFrom(opensearch, cheap)).isEqualTo(idsFrom(mysql, cheap));
    }

    @Test
    void deleteRemovesTheDocumentWithoutASpecialEvent() {
        long productId = writeService.create(request("SKU-IT-3", "Cymophane linen Backpack", "80.00"));
        drain();
        assertThat(idsFrom(opensearch, textQuery("Cymophane"))).containsExactly(productId);

        writeService.delete(productId);
        drain();

        assertThat(idsFrom(opensearch, textQuery("Cymophane"))).isEmpty();
        assertThat(outbox.backlog()).isZero();
    }

    @Test
    void staleDocumentsCannotOverwriteFresherOnes() {
        ProductDocument fresh = document(9_999_001L, 8L, "fresh");
        ProductDocument stale = document(9_999_001L, 3L, "stale");

        assertThat(indexer.write(List.of(fresh), List.of()).indexed()).isEqualTo(1);

        ProductIndexer.IndexResult result = indexer.write(List.of(stale), List.of());

        // external_gte turns the out-of-order write into a counted no-op rather than
        // an error or -- far worse -- a silent regression of the document.
        assertThat(result.staleSkipped()).isEqualTo(1);
        assertThat(result.indexed()).isZero();
        assertThat(result.ok()).isTrue();
    }

    private void drain() {
        OutboxRelay.RelayBatch batch = relay.drainOnce();
        assertThat(batch.failed()).isZero();
        indexAdmin.refresh(properties.opensearch().writeAlias());
    }

    private List<Long> idsFrom(com.acme.catalog.query.ProductSearchPort port, ProductQuery query) {
        SearchSlice slice = port.search(query);
        return slice.ids();
    }

    private long variantIdOf(long productId) {
        Long id = jdbc.queryForObject("SELECT id FROM product_variant WHERE product_id = ?", Long.class, productId);
        return id == null ? -1 : id;
    }

    private CreateProductRequest request(String sku, String name, String price) {
        return new CreateProductRequest(sku, name, name + " description", "BrandIT", "ACTIVE",
                vendorId, categoryId, "EUR", null,
                List.of(new VariantRequest(sku + "-V1", "black", "M", 500, new BigDecimal(price),
                        List.of(new StockRequest("EU-WH1", "EU", 10)))),
                Map.of("material", "cotton"), List.of("new"));
    }

    private ProductQuery textQuery(String text) {
        return new ProductQuery(text, List.of("ACTIVE"), null, null, null, List.of(), List.of(), Map.of(),
                null, null, null, null, SortField.RELEVANCE, SortDirection.DESC, 0, 20, null);
    }

    private ProductQuery priceQuery(String text, BigDecimal min, BigDecimal max) {
        return new ProductQuery(text, List.of("ACTIVE"), null, null, null, List.of(), List.of(), Map.of(),
                min, max, null, null, SortField.PRICE, SortDirection.ASC, 0, 20, null);
    }

    private ProductDocument document(long id, long version, String name) {
        return new ProductDocument(id, version, "SKU-" + id, name, null, "BrandIT", "ACTIVE", "EUR",
                4.0, 1, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z",
                new ProductDocument.VendorPart(1L, "Vendor A", "DE", "GOLD", 4.5),
                new ProductDocument.CategoryPart(1L, "shirts", "/apparel/shirts"),
                List.of(), List.of(), 90_000_000L, 90_000_000L, List.of("EU"), 5, 1);
    }
}
