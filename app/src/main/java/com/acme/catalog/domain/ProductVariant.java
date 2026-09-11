package com.acme.catalog.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "product_variant")
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false, length = 32)
    private String color;

    @Column(name = "size", nullable = false, length = 16)
    private String size;

    @Column(name = "weight_grams", nullable = false)
    private int weightGrams;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @OneToMany(mappedBy = "variant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Inventory> inventory = new ArrayList<>();

    protected ProductVariant() {
    }

    public ProductVariant(String sku, String color, String size, int weightGrams, BigDecimal price) {
        this.sku = sku;
        this.color = color;
        this.size = size;
        this.weightGrams = weightGrams;
        this.price = price;
    }

    void attachTo(Product product) {
        this.product = product;
    }

    public void addInventory(Inventory record) {
        record.attachTo(this);
        this.inventory.add(record);
    }

    public void reprice(BigDecimal price) {
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public String getSku() {
        return sku;
    }

    public String getColor() {
        return color;
    }

    public String getSize() {
        return size;
    }

    public int getWeightGrams() {
        return weightGrams;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getStatus() {
        return status;
    }

    public List<Inventory> getInventory() {
        return inventory;
    }
}
