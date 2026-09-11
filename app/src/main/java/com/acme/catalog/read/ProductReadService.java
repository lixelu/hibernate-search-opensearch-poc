package com.acme.catalog.read;

import com.acme.catalog.api.dto.PageResponse;
import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.query.ProductQuery;
import com.acme.catalog.query.ProductSearchRouter;
import com.acme.catalog.query.SearchSlice;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * search -> hydrate. Two steps, one of which is engine-specific and one of which
 * never changes.
 * <p>
 * The hydration step is what keeps the OpenSearch document small: whatever the API
 * needs for display comes from MySQL by primary key, at a cost of roughly 2-5 ms for
 * a page of 20 -- against 200-2000 ms for the join-and-sort the search replaced.
 * <p>
 * The aggregate record is returned as-is rather than through a separate response
 * DTO: it is already immutable, JSON-shaped, and hand-built for this endpoint, so a
 * second mapping layer would only add drift between two nearly identical files.
 *
 * <h2>Why the engine is asked for more rows than the page holds</h2>
 * An asynchronous index can name a row that MySQL no longer has -- deleted, or
 * changed so it no longer matches the filter -- for as long as the relay is behind.
 * Hydration then finds nothing for that id. Dropping it silently returns a page of
 * 19 where 20 were asked for, which callers experience as data randomly missing.
 * <p>
 * So the engine is asked for a small surplus and the page is trimmed back to size
 * after hydration. Drops are counted, returned in the response, and metered, because
 * a rising drop rate means the index is falling behind and should be visible rather
 * than absorbed.
 * <p>
 * A fixed surplus is not enough on its own: a bulk delete of ten thousand rows can
 * strand more stale ids on one page than any constant covers. So when the page still
 * comes up short, the window widens and the search is retried, bounded by
 * {@code max-refill-factor}. The retry only happens when the first attempt actually
 * fell short, which in steady state is never.
 */
@Service
public class ProductReadService {

    private final ProductSearchRouter router;
    private final ProductAggregateLoader loader;
    private final SearchProperties properties;
    private final MeterRegistry meters;

    public ProductReadService(ProductSearchRouter router, ProductAggregateLoader loader,
                              SearchProperties properties, MeterRegistry meters) {
        this.router = router;
        this.loader = loader;
        this.properties = properties;
        this.meters = meters;
    }

    public PageResponse<ProductAggregate> search(ProductQuery query, SearchProperties.Engine override) {
        Timer.Sample requestSample = Timer.start(meters);
        String engineTag = "unknown";
        String outcome = "success";
        try {
            PageResponse<ProductAggregate> response = doSearch(query, override);
            engineTag = response.engine();
            return response;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            requestSample.stop(meters.timer("catalog.search.request", "engine", engineTag, "outcome", outcome));
        }
    }

    private PageResponse<ProductAggregate> doSearch(ProductQuery query, SearchProperties.Engine override) {
        int want = query.size();
        int window = want + Math.max(0, properties.hydrationSurplus());
        int maxWindow = Math.max(window, want * Math.max(1, properties.maxRefillFactor()));

        SearchSlice slice;
        List<ProductAggregate> hydrated;
        long hydrationMillis = 0;
        int dropped = 0;
        int attempts = 0;

        while (true) {
            slice = router.search(query.withWindow(window), override);

            Timer.Sample hydrationSample = Timer.start(meters);
            long startedAt = System.nanoTime();
            hydrated = loader.loadInOrder(slice.ids());
            hydrationMillis += (System.nanoTime() - startedAt) / 1_000_000;
            hydrationSample.stop(meters.timer("catalog.search.hydration", "engine", slice.engine()));
            dropped = slice.ids().size() - hydrated.size();

            boolean pageIsFull = hydrated.size() >= want;
            boolean engineExhausted = slice.ids().size() < window;
            if (pageIsFull || engineExhausted || window >= maxWindow) {
                break;
            }
            // A bulk delete can strand far more stale ids than the fixed surplus covers,
            // so widen and retry rather than hand back a short page. Bounded, and only
            // reached when the first attempt actually came up short.
            window = Math.min(maxWindow, Math.max(window * 2, want + dropped * 2));
            attempts++;
        }

        if (dropped > 0) {
            meters.counter("catalog.search.stale_dropped", "engine", slice.engine()).increment(dropped);
        }
        if (attempts > 0) {
            meters.counter("catalog.search.refill", "engine", slice.engine()).increment(attempts);
        }
        List<ProductAggregate> items = hydrated.size() > want ? hydrated.subList(0, want) : hydrated;

        return new PageResponse<>(items, slice.totalHits(), slice.totalIsLowerBound(),
                query.page(), want, slice.engine(), slice.tookMillis(), hydrationMillis, dropped);
    }
}
