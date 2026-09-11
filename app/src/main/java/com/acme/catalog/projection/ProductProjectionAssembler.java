package com.acme.catalog.projection;

import com.acme.catalog.common.MinorUnits;
import com.acme.catalog.config.IndexingProperties;
import com.acme.catalog.read.ProductAggregate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure function: aggregate in, document out. No I/O, no Spring dependencies worth
 * mocking -- which makes "does the projection match the SQL semantics?" a plain
 * unit test rather than an integration test.
 */
@Component
public class ProductProjectionAssembler {

    private final IndexingProperties properties;

    public ProductProjectionAssembler(IndexingProperties properties) {
        this.properties = properties;
    }

    /**
     * Which value becomes the OpenSearch external document version. Both are monotonic
     * per aggregate; they differ in what they can see. See IndexingProperties.VersionSource.
     */
    long versionOf(ProductAggregate p) {
        return switch (properties.versionSource()) {
            case AGGREGATE_VERSION -> p.aggregateVersion();
            // Both non-counter sources are computed by the loader, in the same query as
            // the state, and arrive on the aggregate as derivedVersion.
            case MAX_UPDATED_AT, READ_TIMESTAMP -> p.derivedVersion();
        };
    }

    public ProductDocument toDocument(ProductAggregate p) {
        List<BigDecimal> prices = p.variants().stream()
                .map(ProductAggregate.VariantView::price)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList();

        Set<String> inStockRegions = new LinkedHashSet<>();
        int totalStock = 0;
        for (ProductAggregate.VariantView variant : p.variants()) {
            for (ProductAggregate.StockView stock : variant.stock()) {
                totalStock += stock.quantity();
                if (stock.quantity() > 0) {
                    inStockRegions.add(stock.region());
                }
            }
        }

        List<String> attrFlat = new ArrayList<>(p.attributes().size());
        for (Map.Entry<String, String> entry : p.attributes().entrySet()) {
            attrFlat.add(entry.getKey() + "=" + entry.getValue());
        }

        return new ProductDocument(
                p.id(),
                versionOf(p),
                p.sku(),
                p.name(),
                p.description(),
                p.brand(),
                p.status(),
                p.currency(),
                p.ratingAvg() == null ? 0d : p.ratingAvg().doubleValue(),
                p.ratingCount(),
                p.launchDate() == null ? null : p.launchDate().toString(),
                iso(p.createdAt()),
                iso(p.updatedAt()),
                new ProductDocument.VendorPart(p.vendor().id(), p.vendor().name(), p.vendor().country(),
                        p.vendor().tier(), p.vendor().rating() == null ? 0d : p.vendor().rating().doubleValue()),
                new ProductDocument.CategoryPart(p.category().id(), p.category().name(), p.category().path()),
                List.copyOf(p.tags()),
                attrFlat,
                prices.isEmpty() ? 0L : MinorUnits.of(prices.get(0)),
                prices.isEmpty() ? 0L : MinorUnits.of(prices.get(prices.size() - 1)),
                List.copyOf(inStockRegions),
                totalStock,
                p.variants().size());
    }

    private static String iso(Instant instant) {
        // Millisecond precision matches DATETIME(3) and keeps the value inside
        // strict_date_optional_time without relying on nanosecond parsing.
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
