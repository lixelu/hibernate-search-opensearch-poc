package com.acme.catalog.query;

/**
 * Allow-list of sortable fields. Never interpolate a caller-supplied string into
 * SQL or into an OpenSearch sort clause; map through this enum instead.
 */
public enum SortField {
    RELEVANCE,
    PRICE,
    RATING,
    CREATED_AT,
    NAME
}
