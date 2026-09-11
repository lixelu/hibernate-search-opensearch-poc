package com.acme.catalog.query;

import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.query.hibernatesearch.HibernateSearchProductAdapter;
import com.acme.catalog.query.mysql.MySqlProductSearchAdapter;
import com.acme.catalog.query.opensearch.DeepPagingException;
import com.acme.catalog.query.opensearch.OpenSearchProductSearchAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chooses the engine, and is the reason this migration is reversible.
 *
 * <table>
 *   <caption>Modes</caption>
 *   <tr><td>MYSQL</td><td>baseline, unchanged behaviour</td></tr>
 *   <tr><td>SHADOW</td><td>MySQL answers; the shadow candidate runs off the response
 *       path and divergences are counted -- run this until the mismatch rate is
 *       flat</td></tr>
 *   <tr><td>OPENSEARCH</td><td>the hand-rolled pipeline answers; on any error it falls
 *       back to MySQL</td></tr>
 *   <tr><td>HIBERNATE_SEARCH</td><td>the Hibernate Search index answers; same
 *       fallback</td></tr>
 * </table>
 *
 * A per-request {@code engine} override sits on top, which is what makes canarying by
 * tenant, endpoint or percentage a routing concern rather than a code change -- and
 * what lets the two indexing pipelines be compared on identical traffic.
 */
@Component
public class ProductSearchRouter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchRouter.class);

    private final MySqlProductSearchAdapter mysql;
    private final OpenSearchProductSearchAdapter opensearch;
    private final HibernateSearchProductAdapter hibernateSearch;
    private final SearchProperties properties;
    private final MeterRegistry meters;
    /** Shadow comparison must never add latency to the served request. */
    private final ExecutorService shadowExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ProductSearchRouter(MySqlProductSearchAdapter mysql,
                               OpenSearchProductSearchAdapter opensearch,
                               HibernateSearchProductAdapter hibernateSearch,
                               SearchProperties properties,
                               MeterRegistry meters) {
        this.mysql = mysql;
        this.opensearch = opensearch;
        this.hibernateSearch = hibernateSearch;
        this.properties = properties;
        this.meters = meters;
    }

    public SearchSlice search(ProductQuery query, SearchProperties.Engine override) {
        SearchProperties.Engine engine = override != null ? override : properties.engine();
        return switch (engine) {
            case MYSQL -> timed(mysql, query);
            case OPENSEARCH -> searchWithFallback(opensearch, query);
            case HIBERNATE_SEARCH -> searchWithFallback(hibernateSearch, query);
            case SHADOW -> shadow(query);
        };
    }

    private ProductSearchPort candidate(SearchProperties.Engine engine) {
        return switch (engine) {
            case HIBERNATE_SEARCH -> hibernateSearch;
            case MYSQL, SHADOW, OPENSEARCH -> opensearch;
        };
    }

    private SearchSlice searchWithFallback(ProductSearchPort port, ProductQuery query) {
        try {
            return timed(port, query);
        } catch (DeepPagingException e) {
            // Not an availability failure: the caller asked for a window this
            // deployment refuses. Falling back to MySQL would hide the misconfiguration
            // behind a very slow success.
            meters.counter("catalog.search.deep_paging_rejected", "engine", port.engine()).increment();
            throw e;
        } catch (RuntimeException e) {
            meters.counter("catalog.search.fallback", "engine", port.engine()).increment();
            if (!properties.fallbackToMysql()) {
                throw e;
            }
            log.warn("{} query failed, falling back to MySQL: {}", port.engine(), e.toString());
            return timed(mysql, query);
        }
    }

    private SearchSlice shadow(ProductQuery query) {
        ProductSearchPort candidate = candidate(properties.shadowCandidate());
        SearchSlice served = timed(mysql, query);
        shadowExecutor.submit(() -> {
            try {
                SearchSlice shadowed = timed(candidate, query);
                boolean sameOrder = shadowed.ids().equals(served.ids());
                boolean sameTotal = shadowed.totalHits() == served.totalHits();
                meters.counter("catalog.search.shadow",
                        "candidate", candidate.engine(),
                        "match", Boolean.toString(sameOrder && sameTotal)).increment();
                if (!sameOrder || !sameTotal) {
                    // A write landing between the two calls makes them legitimately
                    // differ, so judge the pattern rather than demanding literal zero.
                    log.info("Shadow divergence vs {}: mysql total={} ids={} | candidate total={} ids={}",
                            candidate.engine(), served.totalHits(), served.ids(),
                            shadowed.totalHits(), shadowed.ids());
                }
            } catch (RuntimeException e) {
                meters.counter("catalog.search.shadow",
                        "candidate", candidate.engine(), "match", "error").increment();
                log.debug("Shadow query failed", e);
            }
        });
        return served;
    }

    private SearchSlice timed(ProductSearchPort port, ProductQuery query) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            return port.search(query);
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(meters.timer("catalog.search.engine", "engine", port.engine(), "outcome", outcome));
        }
    }

    @Override
    public void destroy() {
        shadowExecutor.shutdownNow();
    }
}
