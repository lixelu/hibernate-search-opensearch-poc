package com.acme.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request payloads for the write API, grouped in one file to keep the demo navigable. */
public final class WriteRequests {

    private WriteRequests() {
    }

    public record StockRequest(@NotBlank String warehouseCode, @NotBlank String region, int quantity) {
    }

    public record VariantRequest(@NotBlank String sku,
                                 @NotBlank String color,
                                 @NotBlank String size,
                                 int weightGrams,
                                 @NotNull @Positive BigDecimal price,
                                 List<StockRequest> stock) {
    }

    public record CreateProductRequest(@NotBlank String sku,
                                       @NotBlank String name,
                                       String description,
                                       @NotBlank String brand,
                                       @NotBlank String status,
                                       @NotNull Long vendorId,
                                       @NotNull Long categoryId,
                                       String currency,
                                       LocalDate launchDate,
                                       List<VariantRequest> variants,
                                       Map<String, String> attributes,
                                       List<String> tags) {
    }

    public record UpdateProductRequest(String name,
                                       String description,
                                       String status,
                                       BigDecimal ratingAvg,
                                       Integer ratingCount,
                                       Map<String, String> attributes,
                                       List<String> tags) {
    }

    public record RepriceRequest(@NotNull @Positive BigDecimal price) {
    }

    public record StockAdjustmentRequest(@NotBlank String warehouseCode, int quantity) {
    }
}
