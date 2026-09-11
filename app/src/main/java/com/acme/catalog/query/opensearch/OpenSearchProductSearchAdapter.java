package com.acme.catalog.query.opensearch;

import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.query.ProductQuery;
import com.acme.catalog.query.ProductSearchPort;
import com.acme.catalog.query.SearchSlice;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.TotalHitsRelation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The OpenSearch side of the seam.
 * <p>
 * Note what it does NOT return: documents. {@code _source} fetching is switched off
 * entirely, so a page costs the cluster a filter + a sort + N document ids. The API
 * payload is then hydrated from MySQL by primary key. Consequences:
 * <ul>
 *   <li>responses are never stale in their field values, only in their membership
 *       and ordering -- a far easier property to explain to API consumers;</li>
 *   <li>the index carries no display-only data and no PII;</li>
 *   <li>adding a field to the API response does not require a reindex.</li>
 * </ul>
 */
@Component
public class OpenSearchProductSearchAdapter implements ProductSearchPort {

    private final OpenSearchClient client;
    private final ProductQueryTranslator translator;
    private final SearchProperties properties;

    public OpenSearchProductSearchAdapter(OpenSearchClient client,
                                          ProductQueryTranslator translator,
                                          SearchProperties properties) {
        this.client = client;
        this.translator = translator;
        this.properties = properties;
    }

    @Override
    public String engine() {
        return "opensearch";
    }

    @Override
    public SearchSlice search(ProductQuery query) {
        SearchProperties.OpenSearch config = properties.opensearch();
        int from = query.offset();
        if (from > config.maxFrom()) {
            // Never clamp. Silently serving the 10,000th row when the caller asked for
            // the 100,000th is a wrong answer that looks like a right one; a refused
            // request is at least honest about the limit it hit.
            throw new DeepPagingException(from, config.maxFrom());
        }

        SearchRequest request = SearchRequest.of(search -> search
                .index(config.readAlias())
                .query(translator.toQuery(query))
                .sort(translator.toSort(query))
                .from(from)
                .size(query.size())
                .trackTotalHits(track -> config.exactTotalHits()
                        ? track.enabled(true)
                        : track.count((int) config.trackTotalHitsUpTo()))
                // Ids and sort values only.
                .source(source -> source.fetch(false)));

        SearchResponse<Object> response;
        try {
            response = client.search(request, Object.class);
        } catch (IOException e) {
            throw new UncheckedIOException("OpenSearch query failed", e);
        }

        List<Long> ids = response.hits().hits().stream()
                .map(hit -> Long.parseLong(hit.id()))
                .toList();

        long total = response.hits().total() == null ? ids.size() : response.hits().total().value();
        boolean lowerBound = response.hits().total() != null
                && response.hits().total().relation() == TotalHitsRelation.Gte;

        return new SearchSlice(ids, total, lowerBound, engine(), response.took());
    }
}
