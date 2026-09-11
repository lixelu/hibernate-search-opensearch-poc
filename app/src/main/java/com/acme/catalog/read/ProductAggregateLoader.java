package com.acme.catalog.read;

import com.acme.catalog.config.IndexingProperties;
import com.acme.catalog.read.ProductAggregate.CategoryRef;
import com.acme.catalog.read.ProductAggregate.StockView;
import com.acme.catalog.read.ProductAggregate.VariantView;
import com.acme.catalog.read.ProductAggregate.VendorRef;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Batch loader: N product ids in, N aggregates out, in a constant 5 queries.
 * <p>
 * Every query is a primary-key or foreign-key lookup over a bounded id set --
 * the kind of access MySQL is extremely good at, and the only kind of read left
 * on the hot path once filtering and sorting move to OpenSearch.
 */
@Component
public class ProductAggregateLoader {

    private final NamedParameterJdbcTemplate jdbc;
    private final IndexingProperties properties;

    public ProductAggregateLoader(NamedParameterJdbcTemplate jdbc, IndexingProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * The SQL expression that produces the non-counter external version, chosen by
     * configuration. Both are evaluated in the same query that loads the state, which
     * is the whole point: a version that is not read from the same snapshot as the
     * state it describes cannot order that state.
     */
    private String versionExpression() {
        return switch (properties.versionSource()) {
            // Reads the data, so it moves with child updates -- and backwards on child deletes.
            case MAX_UPDATED_AT -> """
                    CAST(UNIX_TIMESTAMP(GREATEST(
                        p.updated_at,
                        COALESCE((SELECT MAX(i.updated_at)
                                    FROM inventory i
                                    JOIN product_variant pv ON pv.id = i.variant_id
                                   WHERE pv.product_id = p.id), p.updated_at)
                    )) * 1000 AS UNSIGNED)""";
            // Does not read the data at all: the database clock at read time, in micros.
            case READ_TIMESTAMP, AGGREGATE_VERSION ->
                    "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(6)) * 1000000 AS UNSIGNED)";
        };
    }

    private record VariantRow(long productId, long id, String sku, String color, String size,
                              int weightGrams, BigDecimal price, String status) {
    }

    private record StockRow(long variantId, String warehouseCode, String region, int quantity, int reserved) {
    }

    private record KeyValueRow(long productId, String key, String value) {
    }

    /** @return aggregates keyed by product id; ids with no row are simply absent. */
    public Map<Long, ProductAggregate> loadByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);

        Map<Long, List<StockRow>> stockByVariant = jdbc.query("""
                        SELECT i.variant_id, i.warehouse_code, i.region, i.quantity, i.reserved
                          FROM inventory i
                          JOIN product_variant pv ON pv.id = i.variant_id
                         WHERE pv.product_id IN (:ids)
                        """, params,
                        (rs, n) -> new StockRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)))
                .stream().collect(Collectors.groupingBy(StockRow::variantId));

        Map<Long, List<VariantView>> variantsByProduct = jdbc.query("""
                        SELECT pv.product_id, pv.id, pv.sku, pv.color, pv.size, pv.weight_grams, pv.price, pv.status
                          FROM product_variant pv
                         WHERE pv.product_id IN (:ids)
                         ORDER BY pv.product_id, pv.id
                        """, params,
                        (rs, n) -> new VariantRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getInt(6), rs.getBigDecimal(7), rs.getString(8)))
                .stream()
                .collect(Collectors.groupingBy(VariantRow::productId,
                        Collectors.mapping(row -> new VariantView(row.id(), row.sku(), row.color(), row.size(),
                                        row.weightGrams(), row.price(), row.status(),
                                        stockByVariant.getOrDefault(row.id(), List.of()).stream()
                                                .map(s -> new StockView(s.warehouseCode(), s.region(), s.quantity(), s.reserved()))
                                                .toList()),
                                Collectors.toList())));

        Map<Long, Map<String, String>> attributesByProduct = jdbc.query("""
                        SELECT a.product_id, a.attr_key, a.attr_value
                          FROM product_attribute a
                         WHERE a.product_id IN (:ids)
                         ORDER BY a.product_id, a.attr_key
                        """, params,
                        (rs, n) -> new KeyValueRow(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .stream()
                .collect(Collectors.groupingBy(KeyValueRow::productId,
                        Collectors.toMap(KeyValueRow::key, KeyValueRow::value, (a, b) -> a, LinkedHashMap::new)));

        Map<Long, List<String>> tagsByProduct = jdbc.query("""
                        SELECT t.product_id, t.tag
                          FROM product_tag t
                         WHERE t.product_id IN (:ids)
                         ORDER BY t.product_id, t.tag
                        """, params,
                        (rs, n) -> new KeyValueRow(rs.getLong(1), rs.getString(2), null))
                .stream()
                .collect(Collectors.groupingBy(KeyValueRow::productId,
                        Collectors.mapping(KeyValueRow::key, Collectors.toList())));

        // derived_version is whichever non-counter external version the configuration
        // selects. It is computed in this query, alongside the state, on purpose.
        List<ProductAggregate> aggregates = jdbc.query(
                "SELECT p.id, p.sku, p.name, p.description, p.brand, p.status, p.currency,\n"
                        + "       p.rating_avg, p.rating_count, p.launch_date, p.aggregate_version,\n"
                        + "       p.created_at, p.updated_at,\n"
                        + "       v.id, v.name, v.country, v.tier, v.rating,\n"
                        + "       c.id, c.name, c.path,\n"
                        + "       " + versionExpression() + " AS derived_version\n"
                        + "  FROM product p\n"
                        + "  JOIN vendor v ON v.id = p.vendor_id\n"
                        + "  JOIN category c ON c.id = p.category_id\n"
                        + " WHERE p.id IN (:ids)", params, (rs, n) -> {
            long id = rs.getLong(1);
            Date launch = rs.getDate(10);
            Timestamp created = rs.getTimestamp(12);
            Timestamp updated = rs.getTimestamp(13);
            return new ProductAggregate(
                    id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7), rs.getBigDecimal(8), rs.getInt(9),
                    launch == null ? null : launch.toLocalDate(),
                    rs.getLong(11),
                    rs.getLong(22),
                    created == null ? null : created.toInstant(),
                    updated == null ? null : updated.toInstant(),
                    new VendorRef(rs.getLong(14), rs.getString(15), rs.getString(16), rs.getString(17), rs.getBigDecimal(18)),
                    new CategoryRef(rs.getLong(19), rs.getString(20), rs.getString(21)),
                    variantsByProduct.getOrDefault(id, List.of()),
                    attributesByProduct.getOrDefault(id, Map.of()),
                    tagsByProduct.getOrDefault(id, List.of()));
        });

        Map<Long, ProductAggregate> byId = new LinkedHashMap<>();
        for (ProductAggregate aggregate : aggregates) {
            byId.put(aggregate.id(), aggregate);
        }
        return byId;
    }

    /** Ordered variant: preserves the order the search engine returned. */
    public List<ProductAggregate> loadInOrder(List<Long> ids) {
        Map<Long, ProductAggregate> byId = loadByIds(ids);
        List<ProductAggregate> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            ProductAggregate aggregate = byId.get(id);
            if (aggregate != null) {
                ordered.add(aggregate);
            }
        }
        return ordered;
    }
}
