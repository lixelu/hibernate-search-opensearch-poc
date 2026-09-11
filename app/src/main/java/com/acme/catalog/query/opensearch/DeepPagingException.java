package com.acme.catalog.query.opensearch;

/**
 * Raised when a request asks for a window past {@code catalog.search.opensearch.max-from}.
 * <p>
 * Two settings must agree for deep paging to work at all: this one, and the index's
 * {@code max_result_window}. Raising them is legitimate when the API contract fixes
 * the paging model -- but the cost is real and belongs in a measurement, not an
 * assumption: every shard builds a priority queue of {@code from + size} entries and
 * ships it to the coordinating node, so the memory cost is
 * {@code shards x (from + size)} per in-flight request.
 */
public class DeepPagingException extends RuntimeException {

    private final int requestedFrom;
    private final int maxFrom;

    public DeepPagingException(int requestedFrom, int maxFrom) {
        super("Requested offset " + requestedFrom + " exceeds catalog.search.opensearch.max-from ("
                + maxFrom + "). Raise it together with the index max_result_window, or page with search_after.");
        this.requestedFrom = requestedFrom;
        this.maxFrom = maxFrom;
    }

    public int getRequestedFrom() {
        return requestedFrom;
    }

    public int getMaxFrom() {
        return maxFrom;
    }
}
