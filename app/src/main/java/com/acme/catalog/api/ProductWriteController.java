package com.acme.catalog.api;

import com.acme.catalog.api.dto.WriteRequests.CreateProductRequest;
import com.acme.catalog.api.dto.WriteRequests.RepriceRequest;
import com.acme.catalog.api.dto.WriteRequests.StockAdjustmentRequest;
import com.acme.catalog.api.dto.WriteRequests.UpdateProductRequest;
import com.acme.catalog.write.ProductWriteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * Unchanged by the migration except for one line per method inside the service.
 * These endpoints keep working -- at the same latency -- when OpenSearch is down.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductWriteController {

    private final ProductWriteService writeService;

    public ProductWriteController(ProductWriteService writeService) {
        this.writeService = writeService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@Valid @RequestBody CreateProductRequest request) {
        Long id = writeService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + id)).body(Map.of("id", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody UpdateProductRequest request) {
        writeService.update(id, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/variants/{variantId}/price")
    public ResponseEntity<Void> reprice(@PathVariable long id, @PathVariable long variantId,
                                        @Valid @RequestBody RepriceRequest request) {
        writeService.repriceVariant(id, variantId, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/variants/{variantId}/stock")
    public ResponseEntity<Void> adjustStock(@PathVariable long id, @PathVariable long variantId,
                                            @Valid @RequestBody StockAdjustmentRequest request) {
        writeService.adjustStock(id, variantId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        writeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
