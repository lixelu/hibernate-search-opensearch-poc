package com.acme.catalog.domain;

import jakarta.persistence.*;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import java.math.BigDecimal;

@Entity
@Table(name = "vendor")
public class Vendor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 2)
    @KeywordField
    private String country;

    @Column(nullable = false, length = 16)
    @KeywordField
    private String tier;

    @Column(nullable = false)
    private BigDecimal rating = BigDecimal.ZERO;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    /**
     * The inverse side, so Hibernate Search can resolve which documents embed this
     * vendor. Lazy and never traversed by application code; it exists purely so that
     * {@code Product.vendor} can be {@code ReindexOnUpdate.DEFAULT} instead of SHALLOW.
     */
    @OneToMany(mappedBy = "vendor", fetch = FetchType.LAZY)
    private java.util.List<Product> products = new java.util.ArrayList<>();

    protected Vendor() {
    }

    public Vendor(String name, String country, String tier, BigDecimal rating) {
        this.name = name;
        this.country = country;
        this.tier = tier;
        this.rating = rating;
    }

    /** Only the mutable, indexed attributes; identity does not change. */
    public void reclassify(String country, String tier) {
        this.country = country;
        this.tier = tier;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCountry() {
        return country;
    }

    public String getTier() {
        return tier;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public String getStatus() {
        return status;
    }
}
