package com.acme.catalog.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(name = "warehouse_code", nullable = false, length = 16)
    private String warehouseCode;

    @Column(nullable = false, length = 8)
    private String region;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int reserved;

    protected Inventory() {
    }

    public Inventory(String warehouseCode, String region, int quantity) {
        this.warehouseCode = warehouseCode;
        this.region = region;
        this.quantity = quantity;
    }

    void attachTo(ProductVariant variant) {
        this.variant = variant;
    }

    public void adjust(int quantity) {
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public ProductVariant getVariant() {
        return variant;
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    public String getRegion() {
        return region;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getReserved() {
        return reserved;
    }
}
