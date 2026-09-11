package com.acme.catalog.domain;

import jakarta.persistence.*;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false)
    private String name;

    /** Materialised path, e.g. {@code /apparel/shirts/oxford}. Prefix-matched in both engines. */
    @Column(nullable = false)
    @KeywordField
    private String path;

    /**
     * The inverse side exists for Hibernate Search, not for the write model, which never
     * traverses it. Without it Hibernate Search cannot find the documents that embed this
     * category, and reindexing them has to be hand-written; with it, renaming a category
     * writes ONE event and the event processor expands it in the background.
     *
     * <p>It is lazy and never loaded by application code, so it costs nothing at runtime.
     * What it does cost is the fan-out itself, which is why {@code CategoryWriteService}
     * refuses a rename whose fan-out exceeds a configured bound.
     */
    @jakarta.persistence.OneToMany(mappedBy = "category", fetch = jakarta.persistence.FetchType.LAZY)
    private java.util.List<Product> products = new java.util.ArrayList<>();

    protected Category() {
    }

    public Category(Category parent, String name, String path) {
        this.parent = parent;
        this.name = name;
        this.path = path;
    }

    /** Only the materialised path changes; the identity does not. */
    public void rename(String newPath) {
        this.path = newPath;
    }

    public Long getId() {
        return id;
    }

    public Category getParent() {
        return parent;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }
}
