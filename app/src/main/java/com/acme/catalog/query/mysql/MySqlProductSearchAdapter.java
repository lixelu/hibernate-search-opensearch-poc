package com.acme.catalog.query.mysql;

import com.acme.catalog.query.ProductQuery;
import com.acme.catalog.query.ProductSearchPort;
import com.acme.catalog.query.SearchSlice;
import com.acme.catalog.query.SortDirection;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The baseline. Same semantics as the OpenSearch adapter, expressed as the SQL a
 * denormalised-but-relational schema forces on you:
 * <ul>
 *   <li>two joins for vendor/category attributes,</li>
 *   <li>correlated sub-selects for price interval and for the price sort key,</li>
 *   <li>EXISTS fan-outs for tags and for regional stock,</li>
 *   <li>a counting sub-select for AND-of-attributes (EAV),</li>
 *   <li>and a second, equally expensive COUNT(*) round trip for the page total.</li>
 * </ul>
 * Every one of those collapses to a single term/range clause on one OpenSearch
 * document, which is the entire performance argument in one class.
 */
@Component
public class MySqlProductSearchAdapter implements ProductSearchPort {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final NamedParameterJdbcTemplate jdbc;

    public MySqlProductSearchAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String engine() {
        return "mysql";
    }

    @Override
    public SearchSlice search(ProductQuery q) {
        long startedAt = System.nanoTime();
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();

        String from = """
                FROM product p
                JOIN vendor v ON v.id = p.vendor_id
                JOIN category c ON c.id = p.category_id
                """;

        if (q.hasText()) {
            predicates.add("MATCH(p.name, p.description) AGAINST (:text IN BOOLEAN MODE)");
            params.addValue("text", booleanModeExpression(q.text()));
        }
        if (notEmpty(q.statuses())) {
            predicates.add("p.status IN (:statuses)");
            params.addValue("statuses", q.statuses());
        }
        if (notEmpty(q.brands())) {
            predicates.add("p.brand IN (:brands)");
            params.addValue("brands", q.brands());
        }
        if (hasText(q.vendorCountry())) {
            predicates.add("v.country = :vendorCountry");
            params.addValue("vendorCountry", q.vendorCountry());
        }
        if (hasText(q.vendorTier())) {
            predicates.add("v.tier = :vendorTier");
            params.addValue("vendorTier", q.vendorTier());
        }
        if (hasText(q.categoryPathPrefix())) {
            predicates.add("c.path LIKE :categoryPath");
            params.addValue("categoryPath", q.categoryPathPrefix() + "%");
        }
        if (q.ratingMin() != null) {
            predicates.add("p.rating_avg >= :ratingMin");
            params.addValue("ratingMin", q.ratingMin());
        }
        if (notEmpty(q.tags())) {
            predicates.add("""
                    EXISTS (SELECT 1 FROM product_tag t
                             WHERE t.product_id = p.id AND t.tag IN (:tags))""");
            params.addValue("tags", q.tags());
        }
        // Interval intersection, identical to the OpenSearch clause
        // priceMax >= :priceMin AND priceMin <= :priceMax -- except that here both
        // bounds have to be recomputed per candidate row.
        if (q.priceMin() != null) {
            predicates.add("(SELECT MAX(pv.price) FROM product_variant pv WHERE pv.product_id = p.id) >= :priceMin");
            params.addValue("priceMin", q.priceMin());
        }
        if (q.priceMax() != null) {
            predicates.add("(SELECT MIN(pv.price) FROM product_variant pv WHERE pv.product_id = p.id) <= :priceMax");
            params.addValue("priceMax", q.priceMax());
        }
        if (hasText(q.inStockRegion())) {
            predicates.add("""
                    EXISTS (SELECT 1 FROM product_variant pv
                              JOIN inventory i ON i.variant_id = pv.id
                             WHERE pv.product_id = p.id
                               AND i.region = :region
                               AND i.quantity > 0)""");
            params.addValue("region", q.inStockRegion());
        }
        if (q.attributes() != null && !q.attributes().isEmpty()) {
            List<String> pairs = new ArrayList<>();
            int i = 0;
            for (Map.Entry<String, String> entry : q.attributes().entrySet()) {
                pairs.add("(a.attr_key = :attrKey" + i + " AND a.attr_value = :attrValue" + i + ")");
                params.addValue("attrKey" + i, entry.getKey());
                params.addValue("attrValue" + i, entry.getValue());
                i++;
            }
            predicates.add("(SELECT COUNT(*) FROM product_attribute a WHERE a.product_id = p.id AND ("
                    + String.join(" OR ", pairs) + ")) = :attrCount");
            params.addValue("attrCount", q.attributes().size());
        }

        String where = predicates.isEmpty() ? "" : "WHERE " + String.join("\n  AND ", predicates) + "\n";
        String orderBy = orderBy(q);

        params.addValue("limit", q.size());
        params.addValue("offset", q.offset());

        String idSql = "SELECT p.id\n" + from + where + "ORDER BY " + orderBy + "\nLIMIT :limit OFFSET :offset";
        String countSql = "SELECT COUNT(*)\n" + from + where;

        List<Long> ids = jdbc.queryForList(idSql, params, Long.class);
        Long total = jdbc.queryForObject(countSql, params, Long.class);

        long tookMillis = (System.nanoTime() - startedAt) / 1_000_000;
        return new SearchSlice(ids, total == null ? 0 : total, false, engine(), tookMillis);
    }

    private String orderBy(ProductQuery q) {
        String dir = q.direction() == SortDirection.ASC ? "ASC" : "DESC";
        String expression = switch (q.sort()) {
            case RELEVANCE -> q.hasText()
                    ? "MATCH(p.name, p.description) AGAINST (:text IN BOOLEAN MODE)"
                    : "p.rating_avg";
            case PRICE -> "(SELECT MIN(pv.price) FROM product_variant pv WHERE pv.product_id = p.id)";
            case RATING -> "p.rating_avg";
            case CREATED_AT -> "p.created_at";
            case NAME -> "p.name";
        };
        // p.id is the stable tie-breaker; without it, two pages can overlap or skip rows.
        return expression + " " + dir + ", p.id ASC";
    }

    /**
     * MySQL boolean-mode syntax is a small language of its own ({@code + - * " ~ <>()}).
     * Feeding raw user input to it produces either syntax errors or silently wrong
     * matches, so reduce the input to word tokens and rebuild the expression.
     */
    private String booleanModeExpression(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String token : NON_WORD.split(raw.trim())) {
            if (token.isBlank()) {
                continue;
            }
            sb.append('+').append(token).append("* ");
        }
        return sb.toString().trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean notEmpty(List<String> values) {
        return values != null && !values.isEmpty() && values.stream().anyMatch(Objects::nonNull);
    }
}
