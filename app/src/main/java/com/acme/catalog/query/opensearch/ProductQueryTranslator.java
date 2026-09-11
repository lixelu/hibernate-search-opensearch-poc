package com.acme.catalog.query.opensearch;

import com.acme.catalog.common.MinorUnits;
import com.acme.catalog.query.ProductQuery;
import com.acme.catalog.query.SortDirection;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Operator;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TermsQueryField;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ProductQuery} -> OpenSearch query DSL. Pure, dependency-free, unit-testable.
 *
 * <h2>Two rules that keep this cheap</h2>
 * <ol>
 *   <li><b>Everything is a {@code filter} clause, not a {@code must} clause</b>,
 *       except free text. Filters skip scoring and are served from the query cache;
 *       this alone is worth a large fraction of the latency win.</li>
 *   <li><b>No nested queries.</b> The EAV attribute filter is N term clauses over
 *       the flattened {@code attrFlat} keyword array.</li>
 * </ol>
 */
@Component
public class ProductQueryTranslator {

    public Query toQuery(ProductQuery q) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        List<Query> filters = new ArrayList<>();

        if (q.hasText()) {
            // The only scoring clause: relevance sorting needs a score to sort by.
            bool.must(Query.of(query -> query.multiMatch(match -> match
                    .query(q.text())
                    .fields("name^3", "description")
                    .operator(Operator.And))));
        }
        addTerms(filters, "status", q.statuses());
        addTerms(filters, "brand", q.brands());
        addTerms(filters, "tags", q.tags());
        addTerm(filters, "vendor.country", q.vendorCountry());
        addTerm(filters, "vendor.tier", q.vendorTier());
        addTerm(filters, "inStockRegions", q.inStockRegion());

        if (hasText(q.categoryPathPrefix())) {
            filters.add(Query.of(query -> query.prefix(prefix -> prefix
                    .field("category.path").value(q.categoryPathPrefix()))));
        }
        if (q.ratingMin() != null) {
            filters.add(range("ratingAvg", q.ratingMin(), null));
        }
        // Interval intersection: the product's [priceMin, priceMax] overlaps the
        // requested window. Two range clauses over two pre-computed scalars replace
        // two correlated MIN()/MAX() sub-selects per candidate row in SQL.
        if (q.priceMin() != null) {
            filters.add(longRange("priceMaxMinor", MinorUnits.of(q.priceMin()), null));
        }
        if (q.priceMax() != null) {
            filters.add(longRange("priceMinMinor", null, MinorUnits.of(q.priceMax())));
        }
        if (q.attributes() != null) {
            for (Map.Entry<String, String> attribute : q.attributes().entrySet()) {
                String pair = attribute.getKey() + "=" + attribute.getValue();
                filters.add(Query.of(query -> query.term(term -> term
                        .field("attrFlat").value(FieldValue.of(pair)))));
            }
        }

        if (filters.isEmpty() && !q.hasText()) {
            return Query.of(query -> query.matchAll(match -> match));
        }
        bool.filter(filters);
        return Query.of(query -> query.bool(bool.build()));
    }

    public List<SortOptions> toSort(ProductQuery q) {
        SortOrder order = q.direction() == SortDirection.ASC ? SortOrder.Asc : SortOrder.Desc;
        List<SortOptions> sorts = new ArrayList<>(2);
        switch (q.sort()) {
            case RELEVANCE -> {
                if (q.hasText()) {
                    sorts.add(SortOptions.of(s -> s.score(score -> score.order(SortOrder.Desc))));
                } else {
                    sorts.add(fieldSort("ratingAvg", order));
                }
            }
            case PRICE -> sorts.add(fieldSort("priceMinMinor", order));
            case RATING -> sorts.add(fieldSort("ratingAvg", order));
            case CREATED_AT -> sorts.add(fieldSort("createdAt", order));
            // "name" is analysed text; the sortable doc-values twin is name.raw.
            case NAME -> sorts.add(fieldSort("name.raw", order));
        }
        // Stable tie-breaker. Without it, two documents with equal sort values can
        // swap places between page 1 and page 2 -- and it is also the field a
        // search_after cursor needs.
        sorts.add(fieldSort("id", SortOrder.Asc));
        return sorts;
    }

    private static SortOptions fieldSort(String field, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order)));
    }

    private static Query longRange(String field, Long gte, Long lte) {
        return Query.of(query -> query.range(r -> {
            r.field(field);
            if (gte != null) {
                r.gte(JsonData.of(gte));
            }
            if (lte != null) {
                r.lte(JsonData.of(lte));
            }
            return r;
        }));
    }

    private static Query range(String field, BigDecimal gte, BigDecimal lte) {
        return Query.of(query -> query.range(r -> {
            r.field(field);
            if (gte != null) {
                r.gte(JsonData.of(gte.doubleValue()));
            }
            if (lte != null) {
                r.lte(JsonData.of(lte.doubleValue()));
            }
            return r;
        }));
    }

    private static void addTerm(List<Query> filters, String field, String value) {
        if (hasText(value)) {
            filters.add(Query.of(query -> query.term(term -> term.field(field).value(FieldValue.of(value)))));
        }
    }

    private static void addTerms(List<Query> filters, String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<FieldValue> fieldValues = values.stream().filter(Objects::nonNull).map(FieldValue::of).toList();
        if (fieldValues.isEmpty()) {
            return;
        }
        filters.add(Query.of(query -> query.terms(terms -> terms
                .field(field)
                .terms(TermsQueryField.of(t -> t.value(fieldValues))))));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
