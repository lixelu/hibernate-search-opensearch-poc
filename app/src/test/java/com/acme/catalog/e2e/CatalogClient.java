package com.acme.catalog.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The service as its callers see it: HTTP only, no Spring context, no direct access to
 * anything the API does not expose. That is deliberate — a suite that reaches past the
 * API stops testing the system and starts testing the code.
 */
public final class CatalogClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;

    public CatalogClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    // ---- responses -------------------------------------------------------------

    public record Page(List<Long> ids, long total, boolean totalIsLowerBound, String engine,
                       long engineMillis, long hydrationMillis, int staleDropped, int status) {
    }

    public record Product(long id, String sku, String name, String status,
                          List<Long> variantIds, List<String> inStockRegions, BigDecimal minPrice) {
    }

    /** A query, built fluently so scenarios read like the API call they make. */
    public static final class Query {
        private final Map<String, List<String>> params = new LinkedHashMap<>();

        public Query text(String value) {
            return add("q", value);
        }

        public Query status(String value) {
            return add("status", value);
        }

        public Query brand(String value) {
            return add("brand", value);
        }

        public Query tag(String value) {
            return add("tag", value);
        }

        public Query attribute(String key, String value) {
            return add("attr", key + ":" + value);
        }

        public Query vendorCountry(String value) {
            return add("vendorCountry", value);
        }

        public Query categoryPath(String value) {
            return add("categoryPath", value);
        }

        public Query priceBetween(String min, String max) {
            return add("priceMin", min).add("priceMax", max);
        }

        public Query inStockRegion(String value) {
            return add("inStockRegion", value);
        }

        public Query sort(String field, String direction) {
            return add("sort", field).add("dir", direction);
        }

        public Query page(int page, int size) {
            return add("page", Integer.toString(page)).add("size", Integer.toString(size));
        }

        private Query add(String key, String value) {
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            return this;
        }

        String toQueryString(String engine) {
            StringBuilder sb = new StringBuilder();
            params.forEach((key, values) -> values.forEach(value -> {
                sb.append(sb.isEmpty() ? "" : "&").append(key).append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }));
            if (engine != null) {
                sb.append(sb.isEmpty() ? "" : "&").append("engine=").append(engine);
            }
            return sb.toString();
        }
    }

    public static Query query() {
        return new Query();
    }

    // ---- reads -----------------------------------------------------------------

    public Page search(Query query, String engine) {
        Response response = get("/api/v1/products?" + query.toQueryString(engine));
        if (response.status() != 200) {
            return new Page(List.of(), -1, false, engine, -1, -1, -1, response.status());
        }
        JsonNode body = response.json();
        List<Long> ids = new ArrayList<>();
        body.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return new Page(ids, body.path("total").asLong(), body.path("totalIsLowerBound").asBoolean(),
                body.path("engine").asText(), body.path("engineMillis").asLong(),
                body.path("hydrationMillis").asLong(), body.path("staleDropped").asInt(), 200);
    }

    public Product productById(long id) {
        Response response = get("/api/v1/products/" + id);
        if (response.status() != 200) {
            return null;
        }
        JsonNode body = response.json();
        List<Long> variantIds = new ArrayList<>();
        List<String> regions = new ArrayList<>();
        BigDecimal min = null;
        for (JsonNode variant : body.path("variants")) {
            variantIds.add(variant.path("id").asLong());
            BigDecimal price = variant.path("price").decimalValue();
            min = (min == null || price.compareTo(min) < 0) ? price : min;
            for (JsonNode stock : variant.path("stock")) {
                if (stock.path("quantity").asInt() > 0 && !regions.contains(stock.path("region").asText())) {
                    regions.add(stock.path("region").asText());
                }
            }
        }
        return new Product(body.path("id").asLong(), body.path("sku").asText(), body.path("name").asText(),
                body.path("status").asText(), variantIds, regions, min);
    }

    // ---- writes ----------------------------------------------------------------

    public long createProduct(String sku, String name, String brand, String status,
                              BigDecimal price, String region, int quantity,
                              Map<String, String> attributes, List<String> tags) {
        Map<String, Object> stock = Map.of("warehouseCode", region + "-WH1", "region", region,
                "quantity", quantity);
        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("sku", sku + "-V1");
        variant.put("color", "black");
        variant.put("size", "M");
        variant.put("weightGrams", 500);
        variant.put("price", price);
        variant.put("stock", List.of(stock));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", sku);
        body.put("name", name);
        body.put("description", name + " — created by the end-to-end suite");
        body.put("brand", brand);
        body.put("status", status);
        body.put("vendorId", 1);
        body.put("categoryId", 3);
        body.put("currency", "EUR");
        body.put("variants", List.of(variant));
        body.put("attributes", attributes);
        body.put("tags", tags);

        Response response = send("POST", "/api/v1/products", body);
        expect(response, 201, "create " + sku);
        return response.json().path("id").asLong();
    }

    public void updateProduct(long id, Map<String, Object> changes) {
        expect(send("PUT", "/api/v1/products/" + id, changes), 204, "update " + id);
    }

    public void repriceVariant(long productId, long variantId, BigDecimal price) {
        expect(send("PUT", "/api/v1/products/" + productId + "/variants/" + variantId + "/price",
                Map.of("price", price)), 204, "reprice " + variantId);
    }

    public void adjustStock(long productId, long variantId, String warehouseCode, int quantity) {
        expect(send("PUT", "/api/v1/products/" + productId + "/variants/" + variantId + "/stock",
                Map.of("warehouseCode", warehouseCode, "quantity", quantity)), 204, "stock " + variantId);
    }

    public void deleteProduct(long id) {
        expect(send("DELETE", "/api/v1/products/" + id, null), 204, "delete " + id);
    }

    // ---- operations ------------------------------------------------------------

    public JsonNode outboxStatus() {
        return get("/api/admin/outbox").json();
    }

    public JsonNode hibernateSearchStatus() {
        return get("/api/admin/hs/status").json();
    }

    public JsonNode reconcile(int sample) {
        return get("/api/admin/reconcile?sample=" + sample).json();
    }

    public boolean healthy() {
        try {
            return get("/actuator/health").status() == 200;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- plumbing --------------------------------------------------------------

    public record Response(int status, String body, ObjectMapper mapper) {
        public JsonNode json() {
            try {
                return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("Response was not JSON: " + body, e);
            }
        }
    }

    public Response get(String path) {
        return exchange(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    private Response send(String method, String path, Object body) {
        HttpRequest.BodyPublisher publisher;
        try {
            publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialise request body", e);
        }
        return exchange(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, publisher));
    }

    private Response exchange(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(
                    builder.timeout(E2eConfig.httpTimeout()).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), json);
        } catch (IOException e) {
            throw new IllegalStateException("Request to " + baseUrl + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private static void expect(Response response, int status, String what) {
        if (response.status() != status) {
            throw new AssertionError(what + " expected HTTP " + status + " but got "
                    + response.status() + ": " + response.body());
        }
    }
}
