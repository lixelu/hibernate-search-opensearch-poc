package com.acme.catalog.write;

import com.acme.catalog.domain.Product;
import org.hibernate.search.mapper.orm.massindexing.MassIndexer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.search.mapper.orm.Search;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * A fan-out the application drives itself, one page of children at a time.
 *
 * <h2>Why this has to exist</h2>
 * Hibernate Search's own fan-out is not paged, and cannot be. Reindexing resolution runs
 * during the writer's session flush: it walks the inverse association to find every
 * document that embeds the changed row, and the reference documentation describes the
 * cost in the same breath as the reason {@code SHALLOW} exists (section 10.10.4) --
 * loading the inverse collection "would lead to loading thousands of entities into the
 * Hibernate ORM session at once, and would perform badly". There is no page size, no
 * cursor, and no way to spread that work over more than one transaction. One statement
 * changing one parent row is one unbounded unit of work inside a web request.
 *
 * <p>So above some size the fan-out has to leave the write path entirely. That is the
 * bargain {@code ReindexOnUpdate.SHALLOW} offers, and its javadoc names the obligation
 * that comes with it: "applications relying on this setting should have periodic batch
 * processes in place to refresh the index of affected entities". This class is that
 * process, and it is the piece most projects forget to write.
 *
 * <h2>The mechanism, and what it actually costs</h2>
 * {@code SearchIndexingPlan.addOrUpdate} takes an entity, and the manual's own example
 * (section 14.6.3) passes {@code entityManager.getReference(...)}, an uninitialised proxy.
 * That reads as though a page of ids could become a page of outbox rows without touching
 * the products. It cannot. Measured with the event processor stopped so nothing else could
 * be loading, 3,079 products produced exactly 3,079 entity loads: Hibernate Search
 * initialises the proxy. There is no id-only enqueue in this API.
 *
 * <p>So this is not a cheaper fan-out. It is a slower one that happens somewhere safe.
 * The library's own resolution loads the inverse collection in one query and writes the
 * events in one transaction; this loads one entity per child across many transactions.
 * What it buys is that the work is paged, restartable, cancellable, and outside the
 * request that changed the parent. Reach for {@link #scopedMassIndex} when the total cost
 * matters more than the granularity.
 *
 * <p>Each page is its own transaction, which is what the manual prescribes for batch
 * processes: "break down the batch process into multiple transactions, each handling a
 * smaller number of elements: the internal document buffer will be cleared after each
 * transaction" (section 14.6.4). It also means a failure halfway leaves the index
 * consistent with the database for every page that did commit, and the run can be
 * restarted from there.
 */
@Service
public class PagedFanOutService {

    private static final Logger log = LoggerFactory.getLogger(PagedFanOutService.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final NamedParameterJdbcTemplate jdbc;
    private final PagedFanOutService self;
    private final Timer pageTimer;

    public PagedFanOutService(NamedParameterJdbcTemplate jdbc,
                              @org.springframework.context.annotation.Lazy PagedFanOutService self,
                              MeterRegistry meters) {
        this.jdbc = jdbc;
        this.self = self;
        this.pageTimer = Timer.builder("catalog.fanout.page")
                .description("One page of a manually driven fan-out, enqueued")
                .register(meters);
    }

    public record FanOutReport(String parent, long parentId, int products, int pages, long millis) {
    }

    /**
     * Enqueues a reindex for every product under one parent, a page at a time.
     *
     * <p>Ordered by id and paged with a keyset, not an offset: the point of this method is
     * that it stays cheap at any catalogue size, and {@code LIMIT n OFFSET m} stops being
     * cheap long before the fan-out does.
     */
    public FanOutReport reindexChildrenOf(String parent, long parentId, int pageSize) {
        String column = switch (parent) {
            case "category" -> "category_id";
            case "vendor" -> "vendor_id";
            default -> throw new IllegalArgumentException("Unknown parent: " + parent);
        };
        long startedAt = System.currentTimeMillis();
        long after = 0;
        int total = 0;
        int pages = 0;
        while (true) {
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM product WHERE " + column + " = :parentId AND id > :after "
                            + "ORDER BY id LIMIT :pageSize",
                    new MapSqlParameterSource()
                            .addValue("parentId", parentId)
                            .addValue("after", after)
                            .addValue("pageSize", pageSize),
                    Long.class);
            if (ids.isEmpty()) {
                break;
            }
            pageTimer.record(() -> self.enqueuePage(ids));
            after = ids.get(ids.size() - 1);
            total += ids.size();
            pages++;
        }
        long millis = System.currentTimeMillis() - startedAt;
        log.info("Manual fan-out for {} {}: {} products in {} pages, {} ms",
                parent, parentId, total, pages, millis);
        return new FanOutReport(parent, parentId, total, pages, millis);
    }

    /**
     * One page, one transaction.
     *
     * <p>The manual's own example (section 14.6.3) passes a {@code getReference} proxy to
     * {@code addOrUpdate}, which reads as though a page of ids could become a page of
     * outbox rows without touching the products. It does not: measured with the event
     * processor stopped so nothing else could be loading, 3,079 products produced exactly
     * 3,079 entity loads. Hibernate Search initialises the proxy. There is no id-only
     * enqueue in this API.
     *
     * <p>That is the cost of doing the fan-out this way, and it is not small. See
     * {@link #scopedMassIndex} for the alternative.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueuePage(List<Long> ids) {
        var plan = Search.session(entityManager).indexingPlan();
        for (Long id : ids) {
            plan.addOrUpdate(entityManager.getReference(Product.class, id));
        }
    }

    /**
     * The same job done by the library instead: a mass index restricted to one parent's
     * children.
     *
     * <p>Section 14.4.5, conditional reindexing. The condition is HQL over the entity
     * being reindexed, it is applied "when querying the database for entities to index",
     * and the {@code MassIndexer} then does what it is built for: stream ids, load in
     * batches across several threads, write to the index directly. The outbox is bypassed
     * entirely, so a fan-out of any size costs zero outbox rows.
     *
     * <p>Two settings are not optional here. {@code purgeAllOnStart(false)} because the
     * manual warns that "even if the reindexing is applied on a subset of entities, by
     * default all entities will be purged at the start", and there is no way to filter
     * that purge -- leave it on and a scoped reindex empties the whole index.
     * {@code mergeSegmentsOnFinish(false)} for the same reason it is off by default.
     *
     * <p>The cost to know about: section 19.3.6. A mass indexer registers itself in the
     * agent table and event processors suspend themselves while it runs. Live indexing
     * stops for the duration, events queue up, and the lag gauge climbs. For a fan-out
     * that finishes in seconds that is a fair trade. For a long one it is an outage of
     * freshness, and the paged enqueue above is the gentler option precisely because it
     * competes with live indexing instead of stopping it.
     */
    @Transactional
    public FanOutReport scopedMassIndex(String parent, long parentId, int threads)
            throws InterruptedException {
        String property = switch (parent) {
            case "category" -> "category.id";
            case "vendor" -> "vendor.id";
            default -> throw new IllegalArgumentException("Unknown parent: " + parent);
        };
        long startedAt = System.currentTimeMillis();
        MassIndexer indexer = Search.session(entityManager).massIndexer(Product.class);
        // The condition is set on the per-type step, which returns a parameter step rather
        // than the indexer, so the global options cannot be chained onto it.
        indexer.type(Product.class)
                .reindexOnly(property + " = :parentId")
                .param("parentId", parentId);
        indexer.purgeAllOnStart(false)
                .mergeSegmentsOnFinish(false)
                .dropAndCreateSchemaOnStart(false)
                .threadsToLoadObjects(threads)
                .batchSizeToLoadObjects(200)
                .idFetchSize(Integer.MIN_VALUE)
                .typesToIndexInParallel(1)
                .startAndWait();
        long millis = System.currentTimeMillis() - startedAt;
        int products = countChildren(parent, parentId);
        log.info("Scoped mass index for {} {}: {} products in {} ms", parent, parentId, products, millis);
        return new FanOutReport(parent, parentId, products, 1, millis);
    }

    private int countChildren(String parent, long parentId) {
        String column = "category".equals(parent) ? "category_id" : "vendor_id";
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product WHERE " + column + " = :parentId",
                new MapSqlParameterSource().addValue("parentId", parentId), Integer.class);
        return count == null ? 0 : count;
    }
}
