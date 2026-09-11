package com.acme.catalog.indexer;

import com.acme.catalog.config.SearchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Index lifecycle: create versioned indices, move aliases, drop old ones.
 * <p>
 * Two deliberate choices:
 * <ol>
 *   <li><b>Mappings live in a JSON resource</b>, not in the Java DSL. They are
 *       reviewed as configuration, diffed like configuration, and can be pasted
 *       straight into Dev Tools when debugging. The typed client is used for
 *       queries, where compile-time checking actually pays.</li>
 *   <li><b>Nothing ever talks to a concrete index name.</b> Reads go through
 *       {@code products_read}, writes through {@code products_write}. A reindex is
 *       therefore an alias move, and a rollback is the reverse alias move.</li>
 * </ol>
 */
@Component
public class IndexAdmin {

    private static final Logger log = LoggerFactory.getLogger(IndexAdmin.class);
    private static final String MAPPING_RESOURCE = "opensearch/product-index.json";

    private final OpenSearchClient client;
    private final SearchProperties properties;
    private final ObjectMapper objectMapper;

    public IndexAdmin(OpenSearchClient client, SearchProperties properties, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Creates {@code <prefix>-v1} plus both aliases when the read alias is absent. */
    public String bootstrapIfMissing() {
        Optional<String> existing = resolveAlias(properties.opensearch().readAlias());
        if (existing.isPresent()) {
            log.info("OpenSearch read alias '{}' -> '{}'", properties.opensearch().readAlias(), existing.get());
            return existing.get();
        }
        String index = properties.opensearch().indexPrefix() + "-v1";
        createIndex(index);
        pointAlias(properties.opensearch().writeAlias(), index);
        pointAlias(properties.opensearch().readAlias(), index);
        log.info("Bootstrapped OpenSearch index '{}' with read/write aliases", index);
        return index;
    }

    public void createIndex(String index) {
        String body = readMapping();
        RawResponse response = execute("PUT", "/" + index, body);
        if (response.status() != 200) {
            throw new IllegalStateException("Failed to create index " + index + ": " + response.body());
        }
    }

    /** Next free version, e.g. products-v1 -> products-v2. */
    public String nextIndexName() {
        String prefix = properties.opensearch().indexPrefix();
        RawResponse response = execute("GET", "/_cat/indices/" + prefix + "-v*?format=json&h=index", null);
        int max = 0;
        if (response.status() == 200 && !response.body().isBlank()) {
            try {
                for (JsonNode node : objectMapper.readTree(response.body())) {
                    String name = node.path("index").asText("");
                    int marker = name.lastIndexOf("-v");
                    if (marker > 0) {
                        try {
                            max = Math.max(max, Integer.parseInt(name.substring(marker + 2)));
                        } catch (NumberFormatException ignored) {
                            // an index that does not follow the convention is not ours to count
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return prefix + "-v" + (max + 1);
    }

    /** Atomically detaches the alias from wherever it is and attaches it to {@code index}. */
    public void pointAlias(String alias, String index) {
        List<String> actions = new ArrayList<>();
        resolveAllIndices(alias).forEach(current ->
                actions.add("{\"remove\":{\"index\":\"" + current + "\",\"alias\":\"" + alias + "\"}}"));
        actions.add("{\"add\":{\"index\":\"" + index + "\",\"alias\":\"" + alias + "\"}}");
        RawResponse response = execute("POST", "/_aliases", "{\"actions\":[" + String.join(",", actions) + "]}");
        if (response.status() != 200) {
            throw new IllegalStateException("Failed to move alias " + alias + " -> " + index + ": " + response.body());
        }
        log.info("Alias '{}' now points at '{}'", alias, index);
    }

    public Optional<String> resolveAlias(String alias) {
        List<String> indices = resolveAllIndices(alias);
        return indices.isEmpty() ? Optional.empty() : Optional.of(indices.get(0));
    }

    public List<String> resolveAllIndices(String alias) {
        RawResponse response = execute("GET", "/_alias/" + alias, null);
        if (response.status() == 404 || response.body().isBlank()) {
            return List.of();
        }
        try {
            List<String> indices = new ArrayList<>();
            Iterator<String> names = objectMapper.readTree(response.body()).fieldNames();
            names.forEachRemaining(indices::add);
            return indices;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Forces segment visibility. Test-only; never call this per write in production. */
    public void refresh(String target) {
        execute("POST", "/" + target + "/_refresh", null);
    }

    public void deleteIndex(String index) {
        execute("DELETE", "/" + index, null);
    }

    /**
     * Documents written to this index since it was created, from the cluster's own
     * counters. Monotonic per index, which makes it usable as a Prometheus counter and
     * therefore as the numerator of an indexing-amplification rate. It counts what
     * Hibernate Search writes too, which nothing inside this application can observe.
     */
    public long indexWriteTotal(String target) {
        RawResponse response = execute("GET", "/" + target + "/_stats/indexing", null);
        if (response.status() != 200) {
            return -1;
        }
        try {
            return objectMapper.readTree(response.body())
                    .path("_all").path("primaries").path("indexing").path("index_total").asLong(-1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Index size on disk, in bytes. Hibernate Search 5 exposed this as
     * {@code Statistics.indexSizes()} and nothing replaced it in 6+; it is worth having
     * because index storage is one of the costs a search migration is judged on, and it
     * is the one that grows quietly.
     */
    public long indexSizeBytes(String target) {
        RawResponse response = execute("GET", "/" + target + "/_stats/store", null);
        if (response.status() != 200) {
            return -1;
        }
        try {
            return objectMapper.readTree(response.body())
                    .path("_all").path("primaries").path("store").path("size_in_bytes").asLong(-1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long documentCount(String target) {
        RawResponse response = execute("GET", "/" + target + "/_count", null);
        if (response.status() != 200) {
            return -1;
        }
        try {
            return objectMapper.readTree(response.body()).path("count").asLong(-1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String readMapping() {
        try (var in = new ClassPathResource(MAPPING_RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + MAPPING_RESOURCE, e);
        }
    }

    private RawResponse execute(String method, String endpoint, String body) {
        Requests.JsonBodyBuilder request = Requests.builder().method(method).endpoint(endpoint);
        if (body != null) {
            request.json(body);
        }
        try (Response response = client.generic().execute(request.build())) {
            return new RawResponse(response.getStatus(),
                    response.getBody().map(Body::bodyAsString).orElse(""));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record RawResponse(int status, String body) {
    }
}
