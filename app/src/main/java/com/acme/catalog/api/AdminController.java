package com.acme.catalog.api;

import com.acme.catalog.config.SearchProperties;
import com.acme.catalog.indexer.IndexAdmin;
import com.acme.catalog.indexer.ReindexService;
import com.acme.catalog.ops.BenchmarkService;
import com.acme.catalog.ops.ReconciliationService;
import com.acme.catalog.outbox.OutboxRelay;
import com.acme.catalog.outbox.OutboxStore;
import com.acme.catalog.query.hibernatesearch.HibernateSearchAdmin;
import com.acme.catalog.seed.DataSeeder;
import com.acme.catalog.write.PagedFanOutService;
import com.acme.catalog.write.ParentAttributeWriteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operational surface of the integration. In a real service these live behind an
 * internal port with authentication; they are here because every one of them is a
 * thing you will need at 3 a.m. and will not want to write then.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final DataSeeder seeder;
    private final OutboxRelay relay;
    private final OutboxStore outbox;
    private final ReindexService reindexService;
    private final ReconciliationService reconciliation;
    private final BenchmarkService benchmark;
    private final IndexAdmin indexAdmin;
    private final SearchProperties properties;
    private final HibernateSearchAdmin hibernateSearch;
    private final PagedFanOutService pagedFanOut;
    private final ParentAttributeWriteService parentAttributes;

    public AdminController(DataSeeder seeder, OutboxRelay relay, OutboxStore outbox,
                           ReindexService reindexService, ReconciliationService reconciliation,
                           BenchmarkService benchmark, IndexAdmin indexAdmin, SearchProperties properties,
                           HibernateSearchAdmin hibernateSearch,
                           PagedFanOutService pagedFanOut,
                           ParentAttributeWriteService parentAttributes) {
        this.seeder = seeder;
        this.relay = relay;
        this.outbox = outbox;
        this.reindexService = reindexService;
        this.reconciliation = reconciliation;
        this.benchmark = benchmark;
        this.indexAdmin = indexAdmin;
        this.properties = properties;
        this.hibernateSearch = hibernateSearch;
        this.pagedFanOut = pagedFanOut;
        this.parentAttributes = parentAttributes;
    }



    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "20000") int products,
                                    @RequestParam(defaultValue = "true") boolean enqueue) {
        seeder.seed(products, enqueue);
        return Map.of("seeded", products, "enqueued", enqueue);
    }

    /** Drives one relay cycle synchronously -- handy in tests and demos. */
    @PostMapping("/relay/drain")
    public OutboxRelay.RelayBatch drain() {
        return relay.drainOnce();
    }

    @GetMapping("/outbox")
    public Map<String, Object> outboxStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("backlog", outbox.backlog());
        status.put("oldestPendingSeconds", outbox.oldestPendingAge().toSeconds());
        return status;
    }

    @GetMapping("/index")
    public Map<String, Object> indexStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("readAlias", properties.opensearch().readAlias());
        status.put("readIndices", indexAdmin.resolveAllIndices(properties.opensearch().readAlias()));
        status.put("writeAlias", properties.opensearch().writeAlias());
        status.put("writeIndices", indexAdmin.resolveAllIndices(properties.opensearch().writeAlias()));
        status.put("documents", indexAdmin.documentCount(properties.opensearch().readAlias()));
        return status;
    }

    @PostMapping("/index/bootstrap")
    public Map<String, Object> bootstrap() {
        return Map.of("index", indexAdmin.bootstrapIfMissing());
    }

    @PostMapping("/reindex")
    public ReindexService.ReindexReport reindex(@RequestParam(defaultValue = "true") boolean moveReadAlias) {
        return reindexService.rebuild(moveReadAlias);
    }

    @PostMapping("/replay")
    public Map<String, Object> replay(@RequestParam(defaultValue = "60") long sinceMinutes) {
        return Map.of("enqueued", reindexService.replayLast(Duration.ofMinutes(sinceMinutes)));
    }

    @GetMapping("/reconcile")
    public ReconciliationService.ReconciliationReport reconcile(@RequestParam(defaultValue = "200") int sample,
                                                                @RequestParam(defaultValue = "false") boolean repair) {
        return reconciliation.check(sample, repair);
    }

    @GetMapping("/benchmark")
    public BenchmarkService.BenchmarkReport benchmark(@RequestParam(defaultValue = "20") int iterations,
                                                      @RequestParam(required = false) List<String> scenario) {
        return benchmark.run(iterations, scenario);
    }

    /** Hibernate Search's outbox and index state, alongside the hand-rolled one. */
    @GetMapping("/hs/status")
    public Map<String, Object> hibernateSearchStatus() {
        return hibernateSearch.status();
    }

    /** The library's backfill. Purges and rebuilds, so the index is incomplete while it runs. */
    @PostMapping("/hs/massindex")
    public HibernateSearchAdmin.MassIndexReport hibernateSearchMassIndex(
            @RequestParam(defaultValue = "4") int threads,
            @RequestParam(defaultValue = "200") int batchSize) throws InterruptedException {
        return hibernateSearch.massIndex(threads, batchSize);
    }

    /**
     * Puts aborted events back in the queue. The runbook step after a cluster outage:
     * Hibernate Search retries an event twice, then abandons it silently and forever.
     */
    @PostMapping("/hs/aborted/reprocess")
    public Map<String, Object> reprocessAborted() {
        return Map.of("requeued", hibernateSearch.reprocessAbortedEvents());
    }

    /**
     * A fan-out the application drives, one page of children at a time. What you run when
     * an association is mapped SHALLOW because its fan-out is too large for a write
     * transaction. Enqueues by id without loading a single product.
     */
    @PostMapping("/hs/fanout/{parent}/{id}")
    public PagedFanOutService.FanOutReport pagedFanOut(@PathVariable String parent,
                                                       @PathVariable long id,
                                                       @RequestParam(defaultValue = "500") int pageSize) {
        return pagedFanOut.reindexChildrenOf(parent, id, pageSize);
    }

    /**
     * The same fan-out done by the library: a mass index scoped to one parent's children.
     * Bypasses the outbox entirely, but suspends live event processing while it runs.
     */
    @PostMapping("/hs/fanout/{parent}/{id}/massindex")
    public PagedFanOutService.FanOutReport scopedMassIndex(@PathVariable String parent,
                                                           @PathVariable long id,
                                                           @RequestParam(defaultValue = "4") int threads)
            throws InterruptedException {
        return pagedFanOut.scopedMassIndex(parent, id, threads);
    }

    /** Throws aborted events away. Leaves the index behind; follow with a mass index. */
    @PostMapping("/hs/aborted/clear")
    public Map<String, Object> clearAborted() {
        return Map.of("discarded", hibernateSearch.clearAbortedEvents());
    }

    /**
     * Turns listener-triggered indexing off and on for the whole application, so a bulk
     * load does not have to pay for indexing row by row. Pause, import, resume, rebuild.
     */
    @PostMapping("/hs/indexing/{state}")
    public Map<String, Object> setIndexing(@PathVariable String state) {
        boolean enabled = "resume".equalsIgnoreCase(state) || "enable".equalsIgnoreCase(state);
        if (enabled) {
            hibernateSearch.resumeIndexing();
        } else {
            hibernateSearch.pauseIndexing();
        }
        return Map.of("listenerTriggeredIndexing", enabled ? "ENABLED" : "PAUSED");
    }

    /**
     * Renames a category, and reports how many products reference it — the size of the
     * fan-out this one row change would cause if the mapping asked for it.
     */
    @PutMapping("/category/{id}/path")
    public ParentAttributeWriteService.ParentChangeResult renameCategory(@PathVariable long id,
                                                                        @RequestParam String path) {
        return parentAttributes.renameCategory(id, path);
    }

    /** The same shape for the other shared parent, so the fan-out pattern is visibly general. */
    @PutMapping("/vendor/{id}/classification")
    public ParentAttributeWriteService.ParentChangeResult reclassifyVendor(
            @PathVariable long id, @RequestParam String country, @RequestParam String tier) {
        return parentAttributes.reclassifyVendor(id, country, tier);
    }

    @GetMapping("/refresh")
    public Map<String, Object> refresh() {
        indexAdmin.refresh(properties.opensearch().writeAlias());
        return Map.of("refreshed", properties.opensearch().writeAlias());
    }
}
