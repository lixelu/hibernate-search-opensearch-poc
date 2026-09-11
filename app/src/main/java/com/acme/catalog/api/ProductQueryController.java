package com.acme.catalog.api;

import com.acme.catalog.api.dto.PageResponse;
import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.query.ProductQuery;
import com.acme.catalog.query.SortDirection;
import com.acme.catalog.query.SortField;
import com.acme.catalog.read.ProductAggregate;
import com.acme.catalog.read.ProductAggregateLoader;
import com.acme.catalog.read.ProductReadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complex GET. Identical contract regardless of which engine answers -- the only
 * externally visible difference is the {@code engine} field in the response envelope.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductQueryController {

    private final ProductReadService readService;
    private final ProductAggregateLoader loader;
    private final SearchProperties properties;

    public ProductQueryController(ProductReadService readService, ProductAggregateLoader loader,
                                  SearchProperties properties) {
        this.readService = readService;
        this.loader = loader;
        this.properties = properties;
    }

    @GetMapping
    public PageResponse<ProductAggregate> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> brand,
            @RequestParam(required = false) List<String> tag,
            @RequestParam(required = false) String vendorCountry,
            @RequestParam(required = false) String vendorTier,
            @RequestParam(required = false) String categoryPath,
            @RequestParam(required = false) BigDecimal priceMin,
            @RequestParam(required = false) BigDecimal priceMax,
            @RequestParam(required = false) String inStockRegion,
            @RequestParam(required = false) BigDecimal ratingMin,
            /** Repeatable: {@code attr=material:cotton&attr=fit:slim}, AND semantics. */
            @RequestParam(required = false) List<String> attr,
            @RequestParam(defaultValue = "CREATED_AT") SortField sort,
            @RequestParam(defaultValue = "DESC") SortDirection dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            /** Per-request engine override; the canary knob. */
            @RequestParam(required = false) SearchProperties.Engine engine) {

        ProductQuery query = new ProductQuery(q, status, vendorCountry, vendorTier, categoryPath, brand, tag,
                parseAttributes(attr), priceMin, priceMax, inStockRegion, ratingMin, sort, dir,
                Math.max(0, page), Math.min(Math.max(1, size), properties.maxPageSize()), null);

        return readService.search(query, engine);
    }

    /**
     * Point lookup by primary key. Stays on MySQL forever: OpenSearch would be slower
     * and staler for exactly the access pattern InnoDB is best at.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductAggregate> byId(@PathVariable long id) {
        ProductAggregate aggregate = loader.loadByIds(List.of(id)).get(id);
        return aggregate == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(aggregate);
    }

    private Map<String, String> parseAttributes(List<String> attr) {
        if (attr == null || attr.isEmpty()) {
            return Map.of();
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String pair : attr) {
            int separator = pair.indexOf(':');
            if (separator > 0) {
                attributes.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
        return attributes;
    }
}
