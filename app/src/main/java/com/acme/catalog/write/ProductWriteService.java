package com.acme.catalog.write;

import com.acme.catalog.api.dto.WriteRequests.CreateProductRequest;
import com.acme.catalog.api.dto.WriteRequests.RepriceRequest;
import com.acme.catalog.api.dto.WriteRequests.StockAdjustmentRequest;
import com.acme.catalog.api.dto.WriteRequests.UpdateProductRequest;
import com.acme.catalog.api.dto.WriteRequests.VariantRequest;
import com.acme.catalog.domain.Category;
import com.acme.catalog.domain.Inventory;
import com.acme.catalog.domain.Product;
import com.acme.catalog.domain.ProductVariant;
import com.acme.catalog.domain.Vendor;
import com.acme.catalog.outbox.OutboxRecorder;
import com.acme.catalog.repo.CategoryRepository;
import com.acme.catalog.repo.ProductRepository;
import com.acme.catalog.repo.VendorRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write path, and the honest measure of what this integration costs it.
 * <p>
 * Every mutating method ends with the same two lines:
 * <pre>
 *     product.bumpVersion();
 *     outbox.productChanged(product.getId());
 * </pre>
 * That is the whole intrusion: an in-memory counter increment and one INSERT into a
 * narrow table, inside the transaction that was already open. No projection is built
 * here, no HTTP call is made here, and OpenSearch being down cannot fail a POST.
 * <p>
 * The two lines could be hidden in a Hibernate entity listener or an AOP aspect.
 * They are not, deliberately: a reviewer can see on the diff of any new write method
 * whether the index will hear about it, and "why is this product stale?" is answered
 * by reading one method rather than by reasoning about interceptor ordering.
 */
@Service
public class ProductWriteService {

    private final ProductRepository products;
    private final VendorRepository vendors;
    private final CategoryRepository categories;
    private final OutboxRecorder outbox;
    private final MeterRegistry meters;

    public ProductWriteService(ProductRepository products, VendorRepository vendors,
                               CategoryRepository categories, OutboxRecorder outbox,
                               MeterRegistry meters) {
        this.products = products;
        this.vendors = vendors;
        this.categories = categories;
        this.outbox = outbox;
        this.meters = meters;
    }

    /**
     * One user-level change. This is the denominator every amplification figure on the
     * dashboard divides by: documents indexed per mutation, outbox rows per mutation.
     * Without it you can see indexing work but not whether it is proportionate.
     */
    private void countMutation(String operation) {
        meters.counter("catalog.write.mutations", "operation", operation).increment();
    }

    @Transactional
    public Long create(CreateProductRequest request) {
        Vendor vendor = vendors.findById(request.vendorId())
                .orElseThrow(() -> new EntityNotFoundException("vendor " + request.vendorId()));
        Category category = categories.findById(request.categoryId())
                .orElseThrow(() -> new EntityNotFoundException("category " + request.categoryId()));

        Product product = new Product(request.sku(), request.name(), request.description(), request.brand(),
                request.status(), vendor, category,
                request.currency() == null ? "EUR" : request.currency(), request.launchDate());

        if (request.variants() != null) {
            for (VariantRequest variantRequest : request.variants()) {
                ProductVariant variant = new ProductVariant(variantRequest.sku(), variantRequest.color(),
                        variantRequest.size(), variantRequest.weightGrams(), variantRequest.price());
                if (variantRequest.stock() != null) {
                    variantRequest.stock().forEach(stock -> variant.addInventory(
                            new Inventory(stock.warehouseCode(), stock.region(), stock.quantity())));
                }
                product.addVariant(variant);
            }
        }
        if (request.attributes() != null) {
            request.attributes().forEach(product::putAttribute);
        }
        if (request.tags() != null) {
            request.tags().forEach(product::addTag);
        }

        Product saved = products.save(product);
        outbox.productChanged(saved.getId());
        countMutation("create");
        return saved.getId();
    }

    @Transactional
    public void update(long productId, UpdateProductRequest request) {
        Product product = require(productId);
        if (request.name() != null || request.description() != null) {
            product.rename(request.name() == null ? product.getName() : request.name(),
                    request.description() == null ? product.getDescription() : request.description());
        }
        if (request.status() != null) {
            product.changeStatus(request.status());
        }
        if (request.ratingAvg() != null && request.ratingCount() != null) {
            product.rate(request.ratingAvg(), request.ratingCount());
        }
        if (request.attributes() != null) {
            request.attributes().forEach(product::putAttribute);
        }
        if (request.tags() != null) {
            request.tags().forEach(product::addTag);
        }
        product.bumpVersion();
        outbox.productChanged(productId);
        countMutation("update");
    }

    /**
     * A price change lives in a child table, but it changes the parent document --
     * hence the parent version bump. Forgetting it is the classic way to end up with
     * an index that is subtly, permanently stale.
     */
    @Transactional
    public void repriceVariant(long productId, long variantId, RepriceRequest request) {
        Product product = require(productId);
        ProductVariant variant = product.getVariants().stream()
                .filter(candidate -> candidate.getId().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("variant " + variantId));
        variant.reprice(request.price());
        product.bumpVersion();
        outbox.productChanged(productId);
        countMutation("reprice");
    }

    @Transactional
    public void adjustStock(long productId, long variantId, StockAdjustmentRequest request) {
        Product product = require(productId);
        ProductVariant variant = product.getVariants().stream()
                .filter(candidate -> candidate.getId().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("variant " + variantId));
        Inventory record = variant.getInventory().stream()
                .filter(candidate -> candidate.getWarehouseCode().equals(request.warehouseCode()))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("warehouse " + request.warehouseCode()));
        record.adjust(request.quantity());
        product.bumpVersion();
        outbox.productChanged(productId);
        countMutation("stock");
    }

    /**
     * Delete needs no special handling: the relay will fail to load the aggregate and
     * turn the same "dirty key" event into a document delete.
     */
    @Transactional
    public void delete(long productId) {
        products.delete(require(productId));
        outbox.productChanged(productId);
        countMutation("delete");
    }

    private Product require(long productId) {
        return products.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("product " + productId));
    }
}
