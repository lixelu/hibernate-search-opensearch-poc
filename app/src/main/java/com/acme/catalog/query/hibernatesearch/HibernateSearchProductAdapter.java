package com.acme.catalog.query.hibernatesearch;

import com.acme.catalog.common.MinorUnits;
import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.domain.Product;
import com.acme.catalog.query.ProductQuery;
import com.acme.catalog.query.ProductSearchPort;
import com.acme.catalog.query.SearchSlice;
import com.acme.catalog.query.SortDirection;
import com.acme.catalog.query.opensearch.DeepPagingException;
import jakarta.persistence.EntityManager;
import org.hibernate.search.engine.search.query.SearchResult;
import org.hibernate.search.engine.search.sort.dsl.SortOrder;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * The third adapter: the same {@link ProductQuery}, expressed in Hibernate Search's
 * DSL against the index Hibernate Search maintains from entity change events.
 *
 * <h2>What differs from the hand-rolled adapter</h2>
 * <ul>
 *   <li>Field names come from the entity mapping, not from a JSON mapping file. The
 *       index and the query cannot drift apart, because both are generated from the
 *       same annotations -- the strongest argument for this library.</li>
 *   <li>{@code select(f -> f.id(Long.class))} returns entity ids without loading
 *       entities, so the ids-plus-hydrate model of D1 survives unchanged.</li>
 *   <li>Filter context is still available, so the "filters do not score" discipline
 *       that buys most of the latency win applies here too.</li>
 * </ul>
 */
@Component
public class HibernateSearchProductAdapter implements ProductSearchPort {

    private final EntityManager entityManager;
    private final SearchProperties properties;

    public HibernateSearchProductAdapter(EntityManager entityManager, SearchProperties properties) {
        this.entityManager = entityManager;
        this.properties = properties;
    }

    @Override
    public String engine() {
        return "hibernate-search";
    }

    @Override
    @Transactional(readOnly = true)
    public SearchSlice search(ProductQuery query) {
        int from = query.offset();
        if (from > properties.opensearch().maxFrom()) {
            throw new DeepPagingException(from, properties.opensearch().maxFrom());
        }

        SearchSession session = Search.session(entityManager);
        long startedAt = System.nanoTime();

        SearchResult<Long> result = session.search(Product.class)
                .select(f -> f.id(Long.class))
                .where(f -> f.bool(bool -> {
                    bool.must(f.matchAll());
                    if (query.hasText()) {
                        bool.must(f.match().fields("name", "description").matching(query.text()));
                    }
                    if (notEmpty(query.statuses())) {
                        bool.filter(f.terms().field("status").matchingAny(query.statuses()));
                    }
                    if (notEmpty(query.brands())) {
                        bool.filter(f.terms().field("brand").matchingAny(query.brands()));
                    }
                    if (notEmpty(query.tags())) {
                        bool.filter(f.terms().field("tags").matchingAny(query.tags()));
                    }
                    if (hasText(query.vendorCountry())) {
                        bool.filter(f.match().field("vendor.country").matching(query.vendorCountry()));
                    }
                    if (hasText(query.vendorTier())) {
                        bool.filter(f.match().field("vendor.tier").matching(query.vendorTier()));
                    }
                    if (hasText(query.inStockRegion())) {
                        bool.filter(f.match().field("inStockRegions").matching(query.inStockRegion()));
                    }
                    if (hasText(query.categoryPathPrefix())) {
                        bool.filter(f.prefix().field("category.path").matching(query.categoryPathPrefix()));
                    }
                    if (query.ratingMin() != null) {
                        bool.filter(f.range().field("ratingAvg").atLeast(query.ratingMin().floatValue()));
                    }
                    // Interval intersection, identical to the other two adapters.
                    if (query.priceMin() != null) {
                        bool.filter(f.range().field("priceMaxMinor").atLeast(MinorUnits.of(query.priceMin())));
                    }
                    if (query.priceMax() != null) {
                        bool.filter(f.range().field("priceMinMinor").atMost(MinorUnits.of(query.priceMax())));
                    }
                    if (query.attributes() != null) {
                        for (Map.Entry<String, String> attribute : query.attributes().entrySet()) {
                            bool.filter(f.match().field("attrFlat")
                                    .matching(attribute.getKey() + "=" + attribute.getValue()));
                        }
                    }
                }))
                .sort(f -> {
                    SortOrder order = query.direction() == SortDirection.ASC ? SortOrder.ASC : SortOrder.DESC;
                    return switch (query.sort()) {
                        case RELEVANCE -> query.hasText()
                                ? f.score().desc().then().field("entityId").asc()
                                : f.field("ratingAvg").order(order).then().field("entityId").asc();
                        case PRICE -> f.field("priceMinMinor").order(order).then().field("entityId").asc();
                        case RATING -> f.field("ratingAvg").order(order).then().field("entityId").asc();
                        case CREATED_AT -> f.field("createdAt").order(order).then().field("entityId").asc();
                        case NAME -> f.field("name_sort").order(order).then().field("entityId").asc();
                    };
                })
                .fetch(from, query.size());

        long tookMillis = (System.nanoTime() - startedAt) / 1_000_000;
        return new SearchSlice(result.hits(), result.total().hitCount(), false, engine(), tookMillis);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean notEmpty(List<String> values) {
        return values != null && !values.isEmpty();
    }
}
