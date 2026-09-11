package com.acme.catalog.projection;

import java.util.List;

/**
 * The read model as OpenSearch stores it: one flat document per product aggregate.
 * <p>
 * Design rules applied here, each of which costs index bytes if broken:
 * <ul>
 *   <li><b>Only filterable/sortable/matchable fields.</b> Display-only payload
 *       stays in MySQL and is hydrated by primary key after the search.</li>
 *   <li><b>No nested types.</b> {@code attrFlat} holds {@code "key=value"} keyword
 *       pairs, so an AND-of-attributes filter is N cheap term clauses instead of N
 *       nested queries over a multiplied doc count.</li>
 *   <li><b>Pre-reduced aggregates.</b> {@code priceMinMinor/priceMaxMinor/
 *       inStockRegions/totalStock} are computed at index time, which is what removes
 *       the correlated sub-selects from the read path.</li>
 *   <li><b>Money as a long in minor units, never {@code scaled_float}.</b> Exact,
 *       and it side-steps a real defect: descending sort on a {@code scaled_float}
 *       field can silently return non-maximal documents. See spec section 9.1.</li>
 *   <li><b>Dates as ISO-8601 strings.</b> Explicit and mapper-independent; no
 *       reliance on a Jackson time module being registered in the JSONP mapper.</li>
 * </ul>
 */
public record ProductDocument(
        long id,
        long version,
        String sku,
        String name,
        String description,
        String brand,
        String status,
        String currency,
        double ratingAvg,
        int ratingCount,
        String launchDate,
        String createdAt,
        String updatedAt,
        VendorPart vendor,
        CategoryPart category,
        List<String> tags,
        List<String> attrFlat,
        long priceMinMinor,
        long priceMaxMinor,
        List<String> inStockRegions,
        int totalStock,
        int variantCount) {

    public record VendorPart(long id, String name, String country, String tier, double rating) {
    }

    public record CategoryPart(long id, String name, String path) {
    }
}
