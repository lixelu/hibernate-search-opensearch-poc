package com.acme.catalog.domain;

import com.acme.catalog.common.MinorUnits;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.search.engine.backend.types.Sortable;
import org.hibernate.search.mapper.pojo.automaticindexing.ReindexOnUpdate;
import org.hibernate.search.mapper.pojo.bridge.mapping.annotation.ValueBridgeRef;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.IndexedEmbedded;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.IndexingDependency;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.ObjectPath;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.PropertyValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregate root. Everything reachable from here is indexed as ONE OpenSearch
 * document, which is what makes the read path join-free.
 */
@Entity
@Table(name = "product")
@Indexed(index = "hs-products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /**
     * Also indexed as a sortable field. The entity id becomes the OpenSearch document
     * id automatically, but a document id is not sortable -- and every sort needs a
     * stable tie-breaker, or pages overlap and search_after has no cursor.
     */
    @GenericField(name = "entityId", sortable = Sortable.YES)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false)
    @FullTextField(analyzer = "standard")
    @KeywordField(name = "name_sort", sortable = Sortable.YES)
    private String name;

    @Column(columnDefinition = "text")
    @FullTextField(analyzer = "standard")
    private String description;

    @Column(nullable = false, length = 120)
    @KeywordField
    private String brand;

    @Column(nullable = false, length = 16)
    @KeywordField
    private String status;

    /**
     * DEFAULT, like {@link #category} below, and for the reason Hibernate Search's own
     * javadoc gives: an application relying on SHALLOW "should have periodic batch
     * processes in place to refresh the index of affected entities in case nested values
     * changed". This project had no such process, so SHALLOW here was not a trade-off,
     * it was a silent staleness bug waiting on the first vendor edit.
     *
     * <p>{@code includePaths} is what keeps the cost sane: naming the two fields sets
     * {@code includeDepth} to 0, so only country and tier are embedded rather than the
     * whole vendor graph. Fan-out for this dataset is at most 3,014 products per vendor,
     * bounded by {@code catalog.indexing.max-parent-fan-out}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_id")
    @IndexedEmbedded(includePaths = {"country", "tier"})
    @IndexingDependency(reindexOnUpdate = ReindexOnUpdate.DEFAULT)
    private Vendor vendor;

    /**
     * DEFAULT, unlike {@link #vendor} above: Category has an inverse collection, so
     * Hibernate Search can resolve "which documents embed this category" by itself.
     * Renaming a category then costs ONE event at write time, and the event processor
     * expands it into one document per product in the background.
     *
     * <p>The alternative — reindexing those products from the write path by hand —
     * was measured and is worse: it has to load every affected entity inside the request
     * transaction, which turned a 105 ms rename into 328 ms and grows linearly. Deferring
     * the expansion keeps the write cheap and moves the cost to indexing lag, where it
     * belongs.
     *
     * <p>The fan-out is still unbounded in principle, so the bound is enforced where it
     * can be: {@code CategoryWriteService} refuses a rename that would touch more than
     * {@code catalog.indexing.max-parent-fan-out} products.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    @IndexedEmbedded(includePaths = {"path"})
    @IndexingDependency(reindexOnUpdate = ReindexOnUpdate.DEFAULT)
    private Category category;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "rating_avg", nullable = false)
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    @Column(name = "launch_date")
    private LocalDate launchDate;

    /**
     * Monotonic version for the WHOLE aggregate. Every mutation -- including ones
     * that only touch a child table -- bumps it, so it can be handed to OpenSearch
     * as an external document version.
     */
    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion = 1L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @GenericField(sortable = Sortable.YES)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductVariant> variants = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "product_attribute", joinColumns = @JoinColumn(name = "product_id"))
    @MapKeyColumn(name = "attr_key", length = 48)
    @Column(name = "attr_value", length = 96, nullable = false)
    private Map<String, String> attributes = new LinkedHashMap<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "product_tag", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "tag", length = 48, nullable = false)
    @KeywordField
    private Set<String> tags = new LinkedHashSet<>();

    protected Product() {
    }

    public Product(String sku, String name, String description, String brand, String status,
                   Vendor vendor, Category category, String currency, LocalDate launchDate) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.brand = brand;
        this.status = status;
        this.vendor = vendor;
        this.category = category;
        this.currency = currency;
        this.launchDate = launchDate;
    }

    /**
     * The single line every write path must not forget. Called by the service layer
     * after any mutation of the aggregate; pairs with
     * {@code OutboxRecorder.recordProductChanged(id)}.
     */
    public void bumpVersion() {
        this.aggregateVersion++;
        this.updatedAt = Instant.now();
    }

    public void addVariant(ProductVariant variant) {
        variant.attachTo(this);
        this.variants.add(variant);
    }

    public void rate(BigDecimal avg, int count) {
        this.ratingAvg = avg;
        this.ratingCount = count;
    }

    public void rename(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public void changeStatus(String status) {
        this.status = status;
    }

    public void putAttribute(String key, String value) {
        this.attributes.put(key, value);
    }

    public void addTag(String tag) {
        this.tags.add(tag);
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getBrand() {
        return brand;
    }

    public String getStatus() {
        return status;
    }

    public Vendor getVendor() {
        return vendor;
    }

    public Category getCategory() {
        return category;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getRatingAvg() {
        return ratingAvg;
    }

    public int getRatingCount() {
        return ratingCount;
    }

    public LocalDate getLaunchDate() {
        return launchDate;
    }

    public long getAggregateVersion() {
        return aggregateVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<ProductVariant> getVariants() {
        return variants;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public Set<String> getTags() {
        return tags;
    }

    // ------------------------------------------------------------------------
    // Derived index fields.
    //
    // These are the Hibernate Search equivalent of ProductProjectionAssembler: the
    // same reductions (price interval, stock regions, flattened attributes), but
    // declared on the entity instead of computed in a separate assembler.
    //
    // @IndexingDependency(derivedFrom = ...) is the load-bearing annotation. It tells
    // Hibernate Search which persistent properties each derived value reads, so a
    // change to a variant's price -- three tables away from this document -- reindexes
    // the parent product automatically. That is the single thing this library provides
    // that a hand-rolled pipeline asks a human to remember on every new write path.
    // ------------------------------------------------------------------------

    /**
     * Ratings as a float, not the BigDecimal field. Hibernate Search maps BigDecimal
     * to {@code scaled_float}, whose descending sort is defective (spec 9.1).
     */
    @Transient
    @GenericField(name = "ratingAvg", sortable = Sortable.YES)
    @IndexingDependency(derivedFrom = @ObjectPath(@PropertyValue(propertyName = "ratingAvg")))
    public float getIndexedRatingAvg() {
        return ratingAvg == null ? 0f : ratingAvg.floatValue();
    }

    @Transient
    @GenericField(name = "priceMinMinor", sortable = Sortable.YES)
    @IndexingDependency(derivedFrom = @ObjectPath({
            @PropertyValue(propertyName = "variants"),
            @PropertyValue(propertyName = "price")}))
    public long getPriceMinMinor() {
        return variants.stream().map(ProductVariant::getPrice).filter(java.util.Objects::nonNull)
                .min(java.util.Comparator.naturalOrder()).map(MinorUnits::of).orElse(0L);
    }

    @Transient
    @GenericField(name = "priceMaxMinor", sortable = Sortable.YES)
    @IndexingDependency(derivedFrom = @ObjectPath({
            @PropertyValue(propertyName = "variants"),
            @PropertyValue(propertyName = "price")}))
    public long getPriceMaxMinor() {
        return variants.stream().map(ProductVariant::getPrice).filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder()).map(MinorUnits::of).orElse(0L);
    }

    /** Regions with stock on hand. Zero-quantity rows must not count, or the two engines disagree. */
    @Transient
    @KeywordField(name = "inStockRegions")
    @IndexingDependency(derivedFrom = {
            @ObjectPath({@PropertyValue(propertyName = "variants"),
                    @PropertyValue(propertyName = "inventory"),
                    @PropertyValue(propertyName = "region")}),
            @ObjectPath({@PropertyValue(propertyName = "variants"),
                    @PropertyValue(propertyName = "inventory"),
                    @PropertyValue(propertyName = "quantity")})})
    public List<String> getInStockRegions() {
        Set<String> regions = new LinkedHashSet<>();
        for (ProductVariant variant : variants) {
            for (Inventory stock : variant.getInventory()) {
                if (stock.getQuantity() > 0) {
                    regions.add(stock.getRegion());
                }
            }
        }
        return List.copyOf(regions);
    }

    @Transient
    @GenericField(name = "totalStock")
    @IndexingDependency(derivedFrom = @ObjectPath({
            @PropertyValue(propertyName = "variants"),
            @PropertyValue(propertyName = "inventory"),
            @PropertyValue(propertyName = "quantity")}))
    public int getTotalStock() {
        int total = 0;
        for (ProductVariant variant : variants) {
            for (Inventory stock : variant.getInventory()) {
                total += stock.getQuantity();
            }
        }
        return total;
    }

    /** EAV flattened to "key=value" keywords, so an AND-of-attributes filter is N term clauses. */
    @Transient
    @KeywordField(name = "attrFlat")
    @IndexingDependency(derivedFrom = @ObjectPath(@PropertyValue(propertyName = "attributes")))
    public List<String> getAttrFlat() {
        List<String> flat = new ArrayList<>(attributes.size());
        attributes.forEach((key, value) -> flat.add(key + "=" + value));
        return flat;
    }
}
