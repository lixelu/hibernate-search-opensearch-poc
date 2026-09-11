package com.acme.catalog.query;

import java.util.List;

/**
 * What a search engine is allowed to return: the ordered ids of the matching page
 * plus the hit count. Deliberately NOT the product payload -- see spec section 4.1
 * ("index as accelerator, not as source of truth").
 *
 * @param ids               matching product ids, in the requested sort order
 * @param totalHits         number of matches
 * @param totalIsLowerBound true when the engine stopped counting at a cap
 * @param engine            which adapter produced this slice
 * @param tookMillis        engine-side latency, excluding hydration
 */
public record SearchSlice(List<Long> ids,
                          long totalHits,
                          boolean totalIsLowerBound,
                          String engine,
                          long tookMillis) {
}
