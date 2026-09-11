package com.acme.catalog.query;

/**
 * The seam. One interface, two adapters, one router in front of them. Adding a
 * third engine later (or removing one) touches nothing outside this package.
 */
public interface ProductSearchPort {

    SearchSlice search(ProductQuery query);

    /** "mysql" or "opensearch"; surfaced in the response for observability. */
    String engine();
}
