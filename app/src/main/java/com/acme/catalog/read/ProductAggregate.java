package com.acme.catalog.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The whole product aggregate, loaded from MySQL in a fixed number of round trips.
 * <p>
 * One loader, two consumers: the API response mapper (hydration after a search)
 * and the projection assembler (document building for the indexer). Keeping those
 * on a single loader is the main reason the OpenSearch integration does not double
 * the amount of read code in the service.
 */
public record ProductAggregate(
        long id,
        String sku,
        String name,
        String description,
        String brand,
        String status,
        String currency,
        BigDecimal ratingAvg,
        int ratingCount,
        LocalDate launchDate,
        long aggregateVersion,
        /**
         * GREATEST(updated_at) across the aggregate's timestamped tables, in epoch
         * milliseconds. The alternative external version for schemas that cannot add
         * a counter column.
         */
        long derivedVersion,
        Instant createdAt,
        Instant updatedAt,
        VendorRef vendor,
        CategoryRef category,
        List<VariantView> variants,
        Map<String, String> attributes,
        List<String> tags) {

    public record VendorRef(long id, String name, String country, String tier, BigDecimal rating) {
    }

    public record CategoryRef(long id, String name, String path) {
    }

    public record VariantView(long id, String sku, String color, String size, int weightGrams,
                              BigDecimal price, String status, List<StockView> stock) {
    }

    public record StockView(String warehouseCode, String region, int quantity, int reserved) {
    }
}
