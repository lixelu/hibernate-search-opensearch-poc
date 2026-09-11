package com.acme.catalog.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Engine-independent description of a "complex GET". Both the MySQL adapter and
 * the OpenSearch adapter consume exactly this -- which is what makes shadow
 * comparison and instant fallback possible.
 */
public record ProductQuery(
        String text,
        List<String> statuses,
        String vendorCountry,
        String vendorTier,
        String categoryPathPrefix,
        List<String> brands,
        List<String> tags,                 // ANY-of
        Map<String, String> attributes,    // ALL-of
        BigDecimal priceMin,
        BigDecimal priceMax,
        String inStockRegion,
        BigDecimal ratingMin,
        SortField sort,
        SortDirection direction,
        int page,
        int size,
        /**
         * Absolute offset override. Null means "page * size", the normal case. Set only
         * when the read service widens the window to refill a page: the window grows but
         * the page must still start where the caller asked, and deriving the offset from
         * the widened size would silently skip rows.
         */
        Integer windowOffset) {

    public int offset() {
        return windowOffset != null ? windowOffset : page * size;
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    /** Same page start, wider window. */
    public ProductQuery withWindow(int newSize) {
        return new ProductQuery(text, statuses, vendorCountry, vendorTier, categoryPathPrefix, brands, tags,
                attributes, priceMin, priceMax, inStockRegion, ratingMin, sort, direction, page, newSize, offset());
    }
}
