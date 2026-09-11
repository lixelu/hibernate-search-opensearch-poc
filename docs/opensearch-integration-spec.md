# Integrating OpenSearch into the Data Service read path

**Status:** proposal for review
**Scope:** complex GET endpoints of the Data Service (Java 21 / Spring Boot, MySQL)
**Reference implementation:** [`app/`](../app) — runnable, with the measurements in this document reproduced from it
**Date:** 2026-09-07

---

## 1. Summary

**Recommendation: adopt OpenSearch as a query accelerator, not as a second source of truth.**

Six decisions carry the proposal:

| # | Decision | Alternative rejected |
|---|---|---|
| D1 | OpenSearch answers *which rows and in what order*; MySQL still returns *what is in them* (hydration by primary key) | Storing the full API payload in the index |
| D2 | Changes reach the index through a **transactional outbox holding only the aggregate id** | Dual writes from the service; CDC/Debezium on day one |
| D3 | Every document carries the aggregate's monotonic version as an **external document version** | Relying on arrival order |
| D4 | Reads and writes address **aliases**, never index names; a rebuild is an alias move | Mutating a live index's mapping |
| D5 | One `SearchPort` interface, two adapters, a router with **shadow mode and automatic fallback** | A feature flag around a second code path |
| D6 | The official **`opensearch-java`** client, mappings kept as JSON resources | Spring Data OpenSearch |

Measured on the reference implementation (100k products / 250k variants / 376k inventory rows / 402k EAV attributes, everything on one laptop):

| | MySQL | OpenSearch | |
|---|---|---|---|
| Worst scenario (EAV + price window + sort by price), p50 | 1521 ms | 10 ms | **152x** |
| Typical filtered page, p50 | 225–255 ms | 11 ms | **20–23x** |
| End-to-end API latency incl. hydration, worst case | 1553 ms | 24 ms | **65x** |
| Storage for the same data | ~207 MB | 23 MB | **11%** |
| POST/PUT latency impact | — | within measurement noise (≤0.2 ms) | |

Result ordering was **identical between engines on every benchmark scenario**, which is the property that makes the cutover a configuration change.

> One finding deserves attention before any mapping is written: **descending sort on an OpenSearch `scaled_float` field can silently return non-maximal documents.** Reproduced on 2.19.6 and 3.8.0. It was caught by comparing engines, not by review. See [§9.1](#91-scaled_float-descending-sort-returns-wrong-results).

---

## 2. The problem, stated precisely

The current GET endpoints are slow not because MySQL is slow, but because the work the query asks for grows with the *result set* rather than with the *page*:

1. **Filters live in five tables.** Vendor attributes, category path, tags, EAV attributes, and regional stock each require a join or an `EXISTS` fan-out. No composite index spans tables, so the optimiser picks one driving index and filters the rest row by row.
2. **Sort keys are derived.** "Cheapest variant price" is `MIN(price)` over a child table. Sorting by it means computing it for every candidate row before discarding all but 20 — a correlated sub-query per row.
3. **Caller-controlled combinations.** With ~10 optional filters and 5 sort fields there are hundreds of shapes. Indexes are chosen per shape; you cannot have hundreds of useful composite indexes on one table, and each one you add taxes every write.
4. **The count costs as much as the page.** `LIMIT 20` bounds the rows returned, not the rows examined, and the `COUNT(*)` for pagination examines all of them again.

This is why index tuning plateaued. Indexes reduce the constant factor; they cannot change the fact that the filter/sort combination is only known at request time and spans tables. The reference implementation's `MySqlProductSearchAdapter` is deliberately written to show it — every clause there is one the schema forces.

**What an inverted index changes.** OpenSearch pre-computes, at write time, exactly what the query needs at read time: one flat document per aggregate, with postings lists per filter term and doc-values columns per sort field. Filtering becomes an intersection of sorted id lists; sorting becomes a scan of one column. The join, the fan-out, and the correlated sub-query are all paid once, off the request path.

That is also its whole limitation: **only queries whose shape you anticipated at index time get the speedup**, and the data is as fresh as your ingestion pipeline.

---

## 3. Options considered

### 3.1 Read-path options

| Option | What it fixes | Why not (as the primary answer) |
|---|---|---|
| More/better MySQL indexes | Constant factors | Already done; cannot span tables or serve derived sort keys |
| Read replicas | Contention with writes | Same query plan, same cost; moves the problem, does not solve it |
| Result caching (Redis) | Repeated identical queries | Long-tail filter combinations have near-zero hit rate; invalidation on a denormalised model is its own project |
| Denormalised read table in MySQL (one wide row per product, maintained by triggers or app code) | Joins | Real improvement, and cheaper to operate than a new datastore — but B-tree indexes still cannot serve arbitrary AND-combinations of ~10 optional predicates, multi-valued tags/attributes need either JSON columns or a side table, and free-text still needs `FULLTEXT`. Fixes joins, not combinatorics |
| Materialised views | Joins | MySQL has none natively; emulating them is the row above |
| **OpenSearch** | Joins, combinatorics, derived sort keys, free text, facets | Eventual consistency; a second system to run; only anticipated query shapes benefit |
| ClickHouse / analytics store | Aggregations over huge scans | Wrong shape: these are selective point-ish queries returning 20 rows, not scans |

**A denormalised MySQL read table deserves a serious look if the filter set is small and fixed.** It is the cheapest thing that works, it needs no new infrastructure, and it can reuse the outbox pipeline proposed here. The case for OpenSearch is specifically the *combinatorics* — arbitrary AND-combinations over multi-valued fields, plus relevance ranking and facet counts, which a row store cannot index for.

### 3.2 Why the prototype's 10–20x is real, and where it comes from

The measurements in §7 decompose the win:

- **Filters, not queries.** Every clause except free text goes in the `filter` context: no scoring, and results are cacheable per segment.
- **Pre-reduced aggregates.** `priceMinMinor`, `priceMaxMinor`, `inStockRegions`, `totalStock` are computed at index time. The two correlated `MIN()`/`MAX()` sub-selects vanish.
- **Flattened EAV.** `attrFlat: ["material=cotton", "fit=slim"]` turns an AND-of-attributes filter into N term intersections. This is the single biggest win in the benchmark (152x) and the query MySQL handles worst.
- **The count is free.** It comes back in the same round trip, capped.

---

## 4. Proposed architecture

```
      POST/PUT/DELETE                                   GET
            |                                            |
            v                                            v
   +-----------------+                        +---------------------+
   | ProductWrite    |                        | ProductQuery        |
   | Service         |                        | Controller          |
   |                 |                        +----------+----------+
   | 1 tx:           |                                   |
   |  - write tables |                        +----------v----------+
   |  - bump version |                        | ProductSearchRouter |  MYSQL | SHADOW | OPENSEARCH
   |  - INSERT outbox|                        +----+-----------+----+  + fallback
   +--------+--------+                             |           |
            |                              +-------v----+  +---v---------------+
            v                              | MySQL      |  | OpenSearch        |
      +-----------+                        | adapter    |  | adapter           |
      |  MySQL    |<-----------------------+ (joins)    |  | (returns ids only)|
      +-----+-----+        hydrate by PK   +------------+  +---------+---------+
            |                    ^                                   |
   outbox   |                    |                                   |
   poll     v                    |                            products_read (alias)
      +-----------+     +--------+---------+                         ^
      | Outbox    |---->| Aggregate loader |                         |
      | Relay     |     | (5 batched PK    |                  products_write (alias)
      +-----+-----+     |  queries)        |                         ^
            |           +--------+---------+                         |
            |                    |                                   |
            |           +--------v---------+      +------------------+
            +---------->| Projection       |----->| Bulk indexer     |
                        | assembler        |      | external_gte     |
                        +------------------+      +------------------+
```

### 4.1 D1 — The index accelerates the query; MySQL still owns the payload

The search returns **ids and sort values only** (`_source` fetching is switched off entirely). The page is then hydrated from MySQL with a batched primary-key lookup.

| | Full document in the index | **Ids + hydrate (proposed)** |
|---|---|---|
| Round trips per request | 1 | 2 (+5 ms measured) |
| Index size | everything the API returns | filter/sort keys only — **23 MB vs ~207 MB** measured |
| Staleness visible to the caller | every field can be stale | only membership and ordering; **field values are always current** |
| Adding a field to the API response | mapping change + full reindex | nothing |
| PII in the search cluster | whatever the response contains | none, by construction |
| Failure mode | stale data served silently | fall back to MySQL and serve correct data |

**A page must never come back short.** An asynchronous index can name a row MySQL no longer has — deleted, or changed so it no longer matches the filter — for as long as the relay is behind. Hydration then finds nothing for that id, and dropping it silently returns 19 rows where 20 were asked for, which callers experience as data randomly going missing. The read service therefore asks the engine for a small surplus (`hydration-surplus`, default 5), trims back to the page size after hydration, and reports the number of dropped ids in the response envelope and as a metric — a rising drop rate means the index is falling behind and should be visible rather than absorbed. Demonstrated with the relay stopped: three products deleted from page one, the page still returns 20 items with `staleDropped: 3` and the deleted ids gone.

The 5 ms hydration is the entire cost of this choice, against a 200–1500 ms saving. It also makes the two adapters return the *same type* (`SearchSlice` = ordered ids + total), which is what makes shadow comparison and instant fallback a few lines rather than a project.

Use the full-document variant only for an endpoint that is read-mostly, latency-critical, and whose response is exactly the indexed fields.

**Hydration at hundreds of millions of rows.** Yes, hydration still touches several tables — it assembles the aggregate. What changes is what the work is proportional to. The join-and-sort query is proportional to the size of the *matching set*: 80,000 matches means 80,000 rows joined, filtered and sorted to return 20. Hydration is proportional to the *page*: 20 primary keys, five index seeks each. Table cardinality enters only through B-tree depth, which grows logarithmically — going from 10 million to 1 billion rows adds roughly one or two levels, so one or two extra page reads per lookup.

The real risk at that scale is not depth, it is **buffer-pool residency**. At 100k products every lookup was a memory hit and hydration cost ~5 ms. At a billion rows the working set no longer fits, so a cold page costs a random read: on NVMe, a page of 20 aggregates might mean a few hundred random reads, putting cold hydration in the 10–50 ms range and warm hydration back near 5 ms. Bounded and predictable either way — which the join is not.

Three things to do about it, in order:

1. **Do not hydrate the full aggregate for a list view.** The reference implementation does, because it has one read path. In production, hydrate a narrow list projection (the fields the list actually renders) with a covering index, and keep the full aggregate load for the detail endpoint.
2. **Watch the aggregate's fan-out.** Hydration cost scales with children per parent, not with table size. Twenty products with a thousand variants each is 20,000 rows, and no amount of index tuning saves that. Cap or paginate child collections in list responses.
3. **Reconsider D1 for the hottest list endpoints.** If hydration measures badly, put the list view's display fields in the document as well and skip the hop for that endpoint, keeping ids-plus-hydrate everywhere else. D1 is a default, not a doctrine — the index grows, but list display fields are small and change rarely.

### 4.2 D2 — Ingestion: a transactional outbox holding only the key

| Approach | Atomic with the write? | Write-path cost | Ordering / replay | Operational weight | Verdict |
|---|---|---|---|---|---|
| Dual write (index inline) | **No** — two systems, no transaction | HTTP call on the request path; search outage becomes a write outage | Manual, error-prone | Low | **Rejected.** Silent divergence on every partial failure |
| Async dual write (fire and forget) | No | Low | Lost on crash | Low | **Rejected.** Same, with less evidence |
| Poll `updated_at` | n/a | None | Misses deletes; long transactions and clock skew create holes | Low | **Rejected as primary**; useful as a repair tool (`replayChangedSince`) |
| **Outbox with the aggregate id (proposed)** | **Yes** — same transaction | **1 narrow INSERT** | At-least-once, idempotent, coalescing | Low — one table, one scheduled poll | **Adopted** |
| Outbox with the payload | Yes | INSERT + build the projection inline | At-least-once | Low | Rejected: doubles storage, moves projection cost onto the write path, cannot coalesce |
| CDC (Debezium + Kafka/Pulsar) | Yes — reads the binlog | **Zero** | Strong, replayable | High — broker, Connect, schema handling, binlog retention | **Later**, if it earns its keep |
| Publish to Pulsar from the service | **No** — MySQL and Pulsar cannot share a transaction | Broker call on the request path | Broker-ordered, but the message may exist without the row or vice versa | Low if the broker already exists | **Rejected** — this is a dual write wearing a broker's clothes |
| Outbox row → relay publishes to Pulsar | **Yes** | 1 narrow INSERT | At-least-once, ordered per key by partition | Low, if the broker already exists | **Adopted if other consumers need the events.** The broker is the transport; the atomic handoff is still the outbox |

The outbox row is `(aggregate_type, aggregate_id, occurred_at, claim fields)` — about 70 bytes, no payload. That choice buys three properties:

- **Coalescing.** Twenty writes to one product inside a poll window produce twenty rows but one aggregate load and one indexed document. Bulk imports and price feeds cost the index almost nothing extra.
- **Idempotent replay.** The relay always reads *current* state, so redelivering a row is a no-op or a newer write. There is no stale payload to apply.
- **Deletes come free.** An id the loader cannot find is a delete. No tombstone events, no special-casing in the write path.

The write path's entire footprint is two lines, kept explicit rather than hidden in an entity listener so that a reviewer can see on any new write method whether the index will hear about it:

```java
product.bumpVersion();
outbox.productChanged(product.getId());
```

`OutboxStore.record` is `@Transactional(propagation = MANDATORY)`: calling it outside a transaction fails loudly rather than quietly reintroducing the dual-write problem.

**Relay mechanics.** `SELECT ... FOR UPDATE SKIP LOCKED` lets every application instance poll the same table concurrently — no leader election, no ShedLock. The cycle is *claim → load → index → delete*, in that order: claim-then-delete is at-least-once across a crash, and D3 makes at-least-once harmless. Deleting first would be at-most-once, i.e. silent data loss in the index.

**Point the relay's aggregate loads at a read replica** when write-primary load matters. The projection reads are the same joins as before, but batched, bounded, and off the request path — and D3 makes reading from a lagging replica safe.

**When to move to CDC:** more than a handful of projections, consumers outside this service, or a relay that cannot keep up with write bursts. The outbox is a strict subset of what CDC gives you, so the migration is additive — the projection assembler and the indexer are unchanged.

### 4.3 D3 — External versioning is what makes the pipeline safe

`product.aggregate_version` is a monotonic counter bumped by **every** write to the aggregate, including writes that only touch a child table (a variant reprice, a stock adjustment). It is sent as the OpenSearch external document version with `external_gte` semantics, so the cluster itself refuses to install an older document.

Four hazards handled by one flag:

- Two relay workers processing the same aggregate cannot interleave into a stale result.
- Redelivery after a crash is a no-op.
- **A full backfill can run against a live index** — an old snapshot row loses to the live version.
- The relay may read from a lagging replica without risk of overwriting a fresher document.

The price is that **a 409 in the bulk response is a normal outcome**, counted as `stale`, not an error. Code that treats it as a failure will retry forever.

Forgetting the parent bump on a child-table write is the classic way to get a permanently stale index; `OutboxToOpenSearchIT.childTableWriteRepricesTheDocument` exists to catch it.

### 4.4 D4 — Aliases and versioned indices

Nothing addresses a concrete index. Reads go through `products_read`, writes through `products_write`, and physical indices are `products-v1`, `products-v2`, …

Rebuild sequence (`ReindexService.rebuild`, measured at **14.7 s for 101,400 documents**):

1. create `products-v(N+1)` from the mapping resource;
2. move the **write** alias to it — live changes now land in the new index;
3. backfill by keyset over the primary key (never `OFFSET`);
4. refresh, then move the **read** alias — readers cut over atomically;
5. drop the old index once you are satisfied.

Step 3 is safe next to step 2 only because of external versioning. The one caveat: between steps 2 and 4 the old index stops receiving updates, so aborting mid-rebuild leaves it behind. Recovery is `replayChangedSince(t)` — move the write alias back and replay the window. Keep the window short and the mapping change in the same deploy.

**Mappings live in `src/main/resources/opensearch/product-index.json`,** not in the Java DSL: they are reviewed as configuration, diffed as configuration, and can be pasted into Dev Tools when debugging. `"dynamic": "strict"` is deliberate — an unmapped field is rejected loudly rather than silently given a guessed type that a later reindex will change.

### 4.5 D5 — Routing, shadow mode, rollout

`ProductSearchRouter` has three modes, switchable per deployment and overridable per request (`?engine=`), which is what makes canarying a routing concern rather than a code change:

- **MYSQL** — baseline, unchanged.
- **SHADOW** — MySQL answers; OpenSearch runs on a virtual thread off the response path; id lists and totals are compared and a `catalog.search.shadow{match}` counter is emitted. Run until the mismatch rate is flat at zero.
- **OPENSEARCH** — OpenSearch answers; any error or timeout falls back to MySQL and increments `catalog.search.fallback`.

Fallback is only tolerable because of D1: both adapters return ordered ids, so the fallback path returns *correct* data, just slower. Measured with the cluster paused: **HTTP 200 served from MySQL in 2.24 s** (the 2 s socket timeout plus the SQL), and **POST unaffected at 118 ms**.

---

## 5. D6 — Client library

| Option | Verdict |
|---|---|
| **`org.opensearch.client:opensearch-java`** (used here) | **Adopted.** Typed query DSL, generated from the server's own spec, no Spring version coupling. Its `generic()` client sends raw JSON where raw JSON is the better artefact (mappings, alias actions) |
| Spring Data OpenSearch | Repository abstraction and derived queries buy little here — every query in this service is a hand-built bool. It adds a version-coupled layer between you and a DSL you must understand anyway, and complex queries end up written in its escape hatch |
| REST high-level client | Deprecated |
| Raw HTTP + hand-rolled JSON | Fine for a handful of queries; loses compile-time checking exactly where the filter combinations grow |
| **Hibernate Search** | A serious alternative at a different altitude — it replaces the projection, the outbox and the indexer, not just the client. See §5.1 |

### 5.1 Hibernate Search — the "buy" to this "build"

Hibernate Search is not a competitor to `opensearch-java`; it is a competitor to roughly half of this document. It maps entities to an OpenSearch index with annotations, reindexes automatically from Hibernate ORM change events, ships an `outbox-polling` coordination strategy that is the same pattern implemented here, provides a mass indexer for backfills, and by default loads hits back through the ORM — which *is* the ids-plus-hydrate model of D1.

What it buys, in order of value:

1. **`@IndexedEmbedded` association reindexing.** It knows that changing a variant's price must reindex the parent product. That is precisely the thing this proposal asks a human to remember on every new write method, and the thing whose omission produces a permanently stale index.
2. Less code to own: no relay, no projection assembler, no index admin.
3. A mass indexer and an explicit indexing API for repairs.

What to verify before adopting it, because each of these can be disqualifying:

1. **Writes that bypass Hibernate are invisible to it** — native SQL, `JdbcTemplate` batch jobs, bulk imports, another service on the same schema, a DBA. In a data service with heavy imports this is the decisive question. Answering it well means keeping a reconciliation job and a way to enqueue repairs regardless of which tool you choose.
2. **Mapping control.** Every data-cost lever in §6.2 — `_source` excludes, `index: false`, `doc_values: false`, `codec`, flattened keyword pairs instead of `nested` — has to be expressible. Where it is not, the index gets larger and you will not notice until it is.
3. **Version matrix.** Hibernate Search ↔ Hibernate ORM ↔ Spring Boot ↔ the OpenSearch backend, whose support has historically trailed the Elasticsearch backend. Confirm the exact quadruple you intend to run.
4. **Projection loading shape.** Reindexing pulls the aggregate through the ORM; the hand-written loader here is five batched key lookups by design. At high write rates, measure it.

**Recommendation:** prototype it as a third `ProductSearchPort` adapter against the benchmark harness already in the repo. That is a few days, it answers all four questions with evidence, and the seam means the loser costs nothing to discard. If it passes, it very likely wins on maintenance cost — which is the criterion that started this evaluation.

Two conventions worth copying:

- The JSONP mapper is a **dedicated `JacksonJsonpMapper`**, not the application's `ObjectMapper`. The index wire format must not shift because someone tunes API serialisation.
- The document is a **record with explicit ISO-8601 date strings**, so the wire format does not depend on which Jackson modules happen to be registered.

---

## 6. Index design and data cost

### 6.1 Measured

| | Rows / docs | Size |
|---|---|---|
| MySQL `product` + `product_variant` + `inventory` + `product_attribute` + `product_tag` (data + indexes) | 100k products, 1.33M child rows | **~207 MB** |
| OpenSearch `products-v1` (single shard, no replica) | 100,800 docs | **23 MB** (≈239 bytes/doc) |
| `outbox_event` at rest | drains to 0 | **~70 bytes/row**, transient |

**Sizing formula for your data:** `index_size ≈ documents × indexed_bytes_per_document × (1 + replicas)`. Take `indexed_bytes_per_document` from a 100k-document sample of *your* fields rather than from a rule of thumb — it is dominated by which fields you index, not by row count. Add ~15% headroom for merges and translog.

### 6.2 The levers that produced 239 bytes/doc

Applied in `product-index.json`; each is a real trade:

| Lever | Saving | Cost |
|---|---|---|
| Index filter/sort keys only; hydrate the rest from MySQL | Largest single factor | +5 ms per request |
| `"_source": {"excludes": ["description"]}` — indexed and searchable, not stored | Large for text-heavy documents | The field cannot be read back from the index; rebuilds must come from MySQL (which they do anyway) |
| `"index": false, "doc_values": false` on display-only fields (`vendor.name`, `category.name`) | Moderate | Cannot filter, sort, or aggregate on them |
| `"norms": false` on text, `"index_options": "docs"` on `description` | Moderate | No length normalisation in scoring; no phrase queries on `description` |
| Flattened `attrFlat` keywords instead of `nested` attributes | Large — `nested` multiplies the Lucene document count by the number of attributes | No per-attribute scoring or key-scoped range queries. Adopt `nested` only for a filter that genuinely needs them |
| `"codec": "best_compression"` | ~15–25% | Slightly slower fetches; irrelevant when `_source` is barely fetched |

**Money is stored as `long` minor units** (`priceMinMinor`), not `scaled_float` — exact, and it avoids the defect in §9.1.

### 6.3 Added load on MySQL

Measured, rather than argued. Same workload run three ways, differencing MySQL's global
status counters (`SHOW GLOBAL STATUS`, not `performance_schema.global_status` — the
latter omits the `Com_*` statement counters and silently reports zero). Reproduce with
`make db-pressure`.

**Idle, 60 seconds, zero traffic** — this is what the pollers cost simply by existing:

| Configuration | statements | transactions | rows updated |
|---|---|---|---|
| Indexing off | 1 | 0 | 0 |
| Hand-rolled relay only | 571 | 285 | 0 |
| Both pipelines | 1,421 | 626 | 28 |

The hand-rolled relay's ~570 statements a minute is exactly its 200 ms poll: 300 polls,
each a `SELECT … SKIP LOCKED` plus a commit. Hibernate Search adds a further ~850, part
polling and part its agent-registry heartbeat — the 28 row updates are that heartbeat,
and they are the only rows either pipeline touches when nothing is happening.

**Per write, 200-write burst** (create, update, reprice, stock adjust, delete), waiting
for both pipelines to fully drain before taking the second snapshot so the whole cost of
indexing those writes is inside the window:

| Per write | Indexing off | Hand-rolled only | Both pipelines |
|---|---|---|---|
| statements | 16.9 | 19.8 | 32.8 |
| transactions | 1.0 | 1.1 | 2.0 |
| InnoDB rows read | 5.8 | 9.3 | 17.4 |
| rows inserted | 1.0 | 2.0 | 3.5 |
| rows deleted | 1.0 | 2.0 | 3.5 |
| index lookups | 5.9 | 7.4 | 14.3 |

Differencing the columns:

- **The hand-rolled outbox adds ~2.9 statements per write** (+17%), one inserted row and
  one deleted row — the outbox row and its acknowledgement — plus ~3.5 rows read for the
  projection load.
- **Hibernate Search adds ~13 statements per write** (+66%), roughly **4.5x the
  hand-rolled pipeline**. Three reasons, all structural: it writes one outbox row per
  touched entity rather than one per aggregate; its event processor loads aggregates
  through the ORM rather than in batched key lookups; and its agent registry keeps a
  heartbeat.

The same figures came out of the end-to-end write tests (32.8 statements per write with
both pipelines on, against 18.4 with neither), so this is not an artefact of the burst
shape.

**How to read this.** Doubling the statement count per write sounds alarming and mostly
is not: these are single-row primary-key operations against a table the buffer pool
holds, and the burst ran 200 writes in 1.8 seconds either way. Set it against what the
read path stops doing — one join-and-sort query plus its `COUNT(*)` twin, at 200–1600 ms
each — and the net effect on the primary is still strongly negative once a meaningful
share of read traffic moves. But size the write side for it, and note that if you run
only one pipeline in production, which one you pick is worth ~13 statements per write.

**One caveat on precision.** Repeating the burst gave 32.8 and 38.4 statements per write
on two runs. The difference is not noise, it is **coalescing**: a burst that finishes
faster gives the relay fewer poll windows, so more writes to the same aggregate collapse
into one projection load. The 3.0-second run read 19.3 rows per write against 17.4 for
the 1.8-second run, which is exactly where that shows up — in reads, not in outbox
inserts, which stayed at 3.5 versus 3.6.

The practical consequence is the useful part: **indexing cost per write falls as write
traffic gets burstier**, because the key-only outbox coalesces. A steady trickle of writes
to distinct aggregates is the expensive case, not a spike. Measure your own mix rather
than taking a single figure from here.


Three new sources, all bounded:

1. **One INSERT per mutating request.** Not measurable against the surrounding transaction (§7.3).
2. **The relay's poll.** One indexed `SELECT ... LIMIT 500 SKIP LOCKED` every 200 ms per instance, plus one `UPDATE` and one `DELETE` per batch. Negligible; make the interval a property so it can be tuned down under pressure.
3. **The relay's aggregate loads.** Five batched primary-key/foreign-key queries per batch of up to 500 aggregates — the same joins as before, but bounded, off the request path, and movable to a read replica.

Set against that, the GET endpoints stop issuing the join-and-sort query *and* its `COUNT(*)` twin. **Net effect on the primary is strongly negative load** once a meaningful share of read traffic moves.

---

## 7. Measured results

Environment: Apple Silicon laptop, Docker Desktop. MySQL 8.4 (1 GB buffer pool), OpenSearch 3.8.0 single node (1 GB heap), Spring Boot 3.5.16 on JDK 21 — all on the same machine, so absolute numbers are laptop numbers; the *ratios* and the *shapes* are what transfer. Dataset: 100,000 products, 250,427 variants, 375,768 inventory rows, 401,975 EAV attributes, 206,495 tags. 15 iterations after 3 warm-up runs; timings cover the engine call only (hydration is identical for both paths and would dilute the comparison).

### 7.1 Engine latency

| Scenario | MySQL p50 | MySQL p95 | OpenSearch p50 | OpenSearch p95 | Speedup | Hits | Same ids |
|---|---|---|---|---|---|---|---|
| `status-page-by-date` | 255 ms | 283 ms | 11 ms | 15 ms | **23x** | 80,213 | yes |
| `vendor-country-sort-by-price` | 49 ms | 52 ms | 9 ms | 12 ms | **5.4x** | 6,508 | yes |
| `category-tags-instock` | 225 ms | 237 ms | 11 ms | 19 ms | **20.5x** | 5,570 | yes |
| `eav-attributes-and-price-window` | 1521 ms | 1743 ms | 10 ms | 15 ms | **152x** | 3,301 | yes |
| `fulltext-plus-filters` | 63 ms | 63 ms | 9 ms | 27 ms | **7.0x** | 1,928 | yes |
| `everything-at-once` | 212 ms | 242 ms | 10 ms | 12 ms | **21x** | 10 | yes |
| `deep-page-100` (offset 2000) | 253 ms | 265 ms | 21 ms | 26 ms | **12x** | 80,213 | yes |

Notes on fairness:

- The MySQL adapter uses a `FULLTEXT` index with `MATCH ... AGAINST`, not `LIKE '%…%'`. Free text is the scenario where MySQL does best (7x) precisely because it is the one thing it has a purpose-built index for.
- The whole dataset fits in the buffer pool after warm-up, so MySQL is not paying disk I/O.
- Both engines run the same semantics, verified by identical id lists.
- Run-to-run variance on a laptop is significant on the OpenSearch side, where the
  absolute numbers are small: a repeat run gave 6-9 ms p50 across the same scenarios
  (so 28x-199x rather than 12x-152x). MySQL numbers were stable to within ~5%. Treat
  the OpenSearch column as "single-digit milliseconds" rather than as a precise value.

### 7.2 End-to-end API latency, including hydration

| Endpoint shape | MySQL wall | OpenSearch wall | of which engine | of which hydration |
|---|---|---|---|---|
| EAV + price window | 1553 ms | **24 ms** | 10 ms | 5.0 ms |
| category + tags + in-stock | 229 ms | **22 ms** | 9 ms | 5.3 ms |
| plain page by date | 251 ms | **18 ms** | 7 ms | 4.6 ms |

Hydration is ~5 ms regardless of engine or scenario — a flat, predictable tax that D1's benefits are bought with.

### 7.3 Write path

200 POSTs (product + variant + inventory + attributes + tags) and 200 PUTs per run; the first run of each configuration is discarded as warm-up.

| | POST p50 | PUT p50 |
|---|---|---|
| Outbox **enabled** (runs 2, 3) | 6.16 / 5.15 ms | 4.15 / 3.62 ms |
| Outbox **disabled** (runs 2, 3) | 5.59 / 5.21 ms | 3.99 / 3.55 ms |

**The outbox INSERT is inside run-to-run noise.** The honest statement is ≤0.2 ms on a small PUT, ~2–4%, and nothing detectable on a POST that already writes five tables. The kill switch (`catalog.indexing.outbox-enabled`) exists both as the A/B mechanism and as a genuine operational lever.

### 7.4 Pipeline

| | Measured |
|---|---|
| Initial index of 100,000 products through the outbox | ~40 s, single relay thread, bulk size 500 |
| Full rebuild + alias cutover (`POST /api/admin/reindex`) | **14.7 s for 101,400 documents** (~6,900 docs/s) |

The rebuild is faster than the outbox-driven first index because it skips the queue, not because it indexes faster. The outbox path pays, per batch of 500, a claim transaction (`SELECT … FOR UPDATE SKIP LOCKED`), an `UPDATE` to stamp the claim and a `DELETE` to acknowledge — roughly 200 rounds of transactional bookkeeping for 100k rows — and loads aggregates 500 at a time. The rebuild walks the primary key directly at 2,000 rows a page with no bookkeeping at all, and ran second, against a warm buffer pool. The practical guidance: **never seed an index by inserting N outbox rows.** Use the backfill; the outbox is for the delta.

| Reconciliation, 500-row sample | 101,400 rows / 101,400 docs, **0 missing, 0 stale** |
| Query with the cluster paused | HTTP 200 from MySQL in 2.24 s |
| POST with the cluster paused | HTTP 201 in 118 ms; backlog drained automatically on recovery |

---

## 8. Operations

**Metrics** (Micrometer, already wired):

| Metric | Meaning | Suggested alert |
|---|---|---|
| `catalog.outbox.backlog` | rows awaiting indexing | sustained growth over 15 min |
| `catalog.outbox.lag.seconds` | age of the oldest unprocessed change | **p99 > 60 s** — the number that means "the index is behind" |
| `catalog.index.documents{outcome}` | indexed / deleted / stale / failed | any `failed` |
| `catalog.search.engine{engine}` | per-engine latency | p95 regression |
| `catalog.search.fallback` | OpenSearch errors served from MySQL | any sustained rate |
| `catalog.search.shadow{match}` | shadow comparison outcome | any `false` during shadow phase |

**Runbook, in the admin endpoints** (put them behind an internal port with auth before this ships):

| Situation | Action |
|---|---|
| Index behind | `GET /api/admin/outbox`; raise `claim-batch-size` or add an instance |
| Suspected drift | `GET /api/admin/reconcile?sample=500&repair=true` — enqueues repairs through the normal pipeline |
| Known bad window | `POST /api/admin/replay?sinceMinutes=N` |
| Mapping change | `POST /api/admin/reindex` — new index, backfill, alias swap |
| Cluster unhealthy | `catalog.search.engine: MYSQL`, or rely on automatic fallback |
| Write path must be isolated | `catalog.indexing.outbox-enabled: false` |
| Poison row (an aggregate that always fails to index) | After `max-attempts` the row stops being claimed but stays in the table, so the backlog and lag alerts keep firing and `outbox_event.last_error` says why. Fix the cause, then `UPDATE outbox_event SET attempts = 0, last_error = NULL WHERE id IN (...)` |

**Reconciliation** compares row counts and samples `aggregate_version` against the index; repairs go through the outbox so there is exactly one code path that writes documents. Schedule it nightly.

Two limits of the sampled version in the reference implementation, both of which matter at hundreds of millions of rows:

- `ORDER BY RAND()` is unusable on a large table, and a random sample of 500 out of 500 million finds a 0.001% drift with probability ≈ 0.
- It walks database → index, so it finds *missing* and *stale* documents but never **orphans**: documents in the index whose row no longer exists. Only the count comparison catches those, and only in aggregate.

The version that scales is a **range checksum**. Partition the primary key into fixed ranges (say 10,000 rows each) and compare, per range, a count and a cheap aggregate of the version column:

```sql
SELECT COUNT(*), SUM(aggregate_version) FROM product WHERE id BETWEEN ? AND ?
```
against the same range in OpenSearch (`range` filter + `value_count` + `sum` on `version`). Equal pairs mean the range agrees on membership *and* freshness; unequal means replay that range. Cost is proportional to the number of ranges, not rows; it detects orphans, misses and staleness in one pass; and it tells you *where* the drift is, so the repair is a range replay rather than a full rebuild. Run it continuously at a low rate rather than nightly in one burst.

**Cluster sizing** (starting point, to be revised against your real volume): 3 data nodes for quorum and availability, heap at 50% of RAM and never above ~31 GB, one primary shard per 10–50 GB of index, replicas ≥ 1 in production. At 23 MB per 100k products this workload is a single-shard index for a long time — resist the urge to over-shard; each shard has fixed overhead and slows every query that fans out.

---

## 9. Risks and findings

### 9.1 `scaled_float` descending sort returns wrong results

**Found while building the reference implementation.** Ordering diverged between engines on a deep page; the divergence was in OpenSearch.

A descending sort on a `scaled_float` field can return documents that are **not** the maximum, while a `max` aggregation on the same field, in the same query, reports the true maximum:

```
GET products/_search {"sort":[{"ratingAvg":"desc"}], "size":1}   -> 3.12
GET products/_search {"aggs":{"m":{"max":{"field":"ratingAvg"}}}} -> 5.0
```

Verified with `docvalue_fields` that the true-maximum document exists, is `ACTIVE`, and holds doc-value `5.0` — and is simply not returned first. Reproduced with a 60-line script on **OpenSearch 2.19.6 and 3.8.0**, single shard, force-merged to one segment, with and without concurrent segment search. It is data-dependent: sometimes the top hit is off by one tick (199.97 instead of 200.00), sometimes badly wrong.

| Field type | Ascending sort | Descending sort |
|---|---|---|
| `scaled_float` | correct | **WRONG** |
| `float`, `double`, `half_float`, `long`, `integer` | correct | correct |

Adding a `range` filter to the same query masks it (results become correct), which is why it survives casual testing.

**Mitigations, applied here:**

1. **Do not use `scaled_float` for any field you sort on.** Money → `long` in minor units. Ratings → `float`. Both verified.
2. **Shadow mode is not optional.** Comparing id lists against MySQL is what surfaced this; no amount of code review would have.
3. Pin the OpenSearch version, and re-run the parity check on every upgrade.

The broader lesson generalises past this one bug: an engine change is a correctness change, and the only cheap proof is running both and diffing.

### 9.2 Substring and wildcard filters are the one weak case

Everything in §7 measures *term* filters, where OpenSearch looks up a precomputed postings list. Partial-string matching is a different cost class, and it is worth knowing before an endpoint that does `LIKE '%...%'` is migrated on the assumption it will behave like the rest.

Three shapes, in increasing cost:

| Shape | What the engine does | Cost driver |
|---|---|---|
| `match` on an analysed field (`cotton`) | Looks up one term | Matched documents |
| `prefix` on a keyword (`SKU-1*`) | Scans a contiguous range of the sorted term dictionary | Terms in that range |
| `wildcard` / `regexp` with a leading wildcard (`*cotton*`) | Scans **every term** in the field's dictionary, then unions the postings of each match | **Distinct terms in the field** |

Measured on the 101,363-document index — note the cost driver is the dictionary, not the corpus:

| Query | Hits | Capped count | Exact count |
|---|---|---|---|
| `term brand = Brand1` | 2,500 | 4 ms | 3 ms |
| `match name : cotton` | 21,283 | 4 ms | 3 ms |
| `prefix name.raw : Classic` | 14,626 | 3 ms | 3 ms |
| `wildcard name.raw : *cotton*` | 21,283 | 6 ms | 4 ms |
| `wildcard sku : *123*` | 307 | 6 ms | 4 ms |
| `regexp name.raw : .*wool.*` | 19,967 | 5 ms | 4 ms |

**Exact counting stays free in every shape** — the delta is zero or negative throughout. What grows is the base cost of the query, and it grows with the size of the term dictionary. Isolating that with unique 16-character codes, one shard, one term per document:

| Distinct terms | `wildcard *a1b*` |
|---|---|
| 100,000 | 9 ms |
| 400,000 | 14 ms |
| 1,000,000 | 43 ms |

Extrapolate with care, but the shape is clear: a leading wildcard over a field with 100 million distinct values is seconds, not milliseconds, per shard. It does parallelise across shards — unlike deep paging, more shards *help* here.

Against MySQL on the same data, `LIKE '%cotton%'` with the joins and an exact `COUNT(*)` took **31 ms** versus **4 ms** for the OpenSearch wildcard. Still a win, but ~8x rather than the 20–150x of term filters, and for a good reason: **this is the one case where OpenSearch does not change the complexity class.** Both engines scan; OpenSearch just scans a much smaller structure (distinct terms rather than rows). Where values are near-unique, that advantage narrows.

**The fix is to index for it rather than to scan for it.** A trigram sub-field turns substring matching back into a term lookup:

| Approach, 400k documents | Query | p50 | Index size |
|---|---|---|---|
| `keyword` + leading wildcard | `*a1b*` | 17 ms | 22.0 MB |
| trigram sub-field + phrase match | `a1b` | **2 ms** | 29.8 MB |

Identical results, 8x faster, for about 35% more index on that field. Choose deliberately:

- **Word matching** ("cotton" as a word) — use `match` on the analysed field. Fastest of all, and free.
- **Prefix only** ("SKU-1…") — `prefix` on a keyword is already cheap; `edge_ngram` makes it cheaper.
- **True substring** ("otto" inside a word) — add an ngram sub-field, and budget the index growth. Do not leave it as a wildcard on a high-cardinality field.

### 9.3 Pagination and totals

- **Deep paging is workable when the API contract demands it, and it is measured.** `from + size` is bounded by two settings that must agree: `catalog.search.opensearch.max-from` and the index's `max_result_window`. Raising both to 150,000 supports a `from=100000, size=1000` contract. Measured end-to-end through the API, on one shard, page hydrated from MySQL in both cases:

  | Window | MySQL wall | OpenSearch wall |
  |---|---|---|
  | `from=0, size=20` | 284 ms | **19 ms** |
  | `from=0, size=1000` | 401 ms | **220 ms** |
  | `from=20000, size=1000` | 402 ms | **199 ms** |
  | `from=60000, size=1000` | 444 ms | **214 ms** |

  Two things to take from this. First, cost is nearly flat in `from` and dominated by `size`: fetching 1,000 hits and hydrating 1,000 rows costs both engines 70–110 ms apiece. Second, **a `size=1000` contract is the least favourable case for this migration** — the advantage falls from ~15x at 20 rows to ~2x at 1,000, because the fixed per-row costs both engines pay start to dominate. It is still a win; it is not the headline win.

  The caveat that does not appear in these numbers: this is a **single-shard** index. Every shard builds a priority queue of `from + size` and ships it to the coordinating node, so the cost of deep paging multiplies by the shard count. At hundreds of millions of documents you will have several shards, and `from=100000` then means merging `shards × 101,000` entries per request. Measure at your real shard count before committing, and keep this index's shard count as low as the data volume allows.

  `search_after` remains the cheap way to page deeply — constant cost per page, and the tiebreaker sort it needs is already in place (`id ASC` closes every sort clause). It is a cursor, so it supports next/previous but not jumping to page 500. Where the API contract fixes `from`/`size` and cannot change, raise the two window settings and pay the measured cost.

- **Never clamp an out-of-range window.** Silently serving row 10,000 when the caller asked for row 100,000 is a wrong answer that looks like a right one. Requests past `max-from` are refused with a `400` naming both the requested offset and the limit.
- **Exact totals: measured, and free at this scale.** The initial draft capped `track_total_hits` at 10,000 and called the resulting lower bound the one visible contract difference of the migration. That was pessimism, not measurement. Counting every match instead of stopping at the cap costs, at 100k documents:

  | Matching documents | Capped at 10k, p50 | Exact count, p50 | Delta |
  |---|---|---|---|
  | 21 | 5.9 ms | 4.5 ms | −1.4 ms |
  | 6,971 | 6.1 ms | 5.1 ms | −1.0 ms |
  | 20,058 | 5.2 ms | 4.4 ms | −0.7 ms |
  | 81,614 | 5.3 ms | 5.9 ms | +0.6 ms |
  | 101,401 (all) | 4.2 ms | 4.3 ms | +0.1 ms |

  Every delta is inside run-to-run noise. Counting a filter-only query is a cardinality operation over postings lists, not a per-document evaluation, so it is far cheaper than the `COUNT(*)` over joins that MySQL was already paying on every request. `exact-total-hits: true` is therefore the **default**, and totals match MySQL exactly.

  Two caveats that do not go away: the cost is linear in matched documents, so **re-measure at your own cardinality** before assuming it stays free when a filter matches tens of millions of rows; and an exact count from an asynchronous index is exact *as of what the index has seen*. If "exact" means "transactionally consistent with this instant", no read model can provide it and those endpoints stay on MySQL.
- Every sort ends with a stable tiebreaker. Without one, two documents with equal sort values can swap between pages.

### 9.4 Consistency

Steady-state lag is sub-second; the alert threshold is 60 s. Read-your-writes is **not** provided. Options, in order of preference:

1. After a write, read that entity by primary key (`GET /api/v1/products/{id}` never touches OpenSearch).
2. For a UI that must show the new item in a list, insert it client-side.
3. Session-sticky bypass to MySQL for N seconds after a write, if a caller truly needs it.

Never use `refresh=true` on the index call to paper over this: it destroys indexing throughput. `refresh_interval: 1s` is the setting; `wait_for` is the per-request escape hatch.

### 9.5 Hard deletes

The relay turns a missing aggregate into a document delete, which is correct but carries no version. A delete racing a re-create of the same id could theoretically resurrect a document. If ids are reused or hard deletes are frequent, switch to a soft-delete flag and filter on it — then deletes become ordinary versioned updates.

### 9.6 Data classification

Because the index holds only filter and sort keys, no PII reaches the search cluster today. Keep it that way deliberately: adding a customer name to the index for a "search by contact" feature is a data-protection decision, not a mapping decision.

### 9.7 What must not move

- Point lookups by primary key — InnoDB is better at these and always fresh.
- Anything transactional, or any read a write decision depends on.
- Endpoints whose filters are not known in advance (arbitrary ad-hoc reporting).
- Exact counts on very large result sets.

---

## 10. Rollout plan

| Phase | Work | Exit criteria |
|---|---|---|
| 0. Foundations | Cluster provisioned; mapping reviewed; outbox table and relay deployed with `engine: MYSQL` | Backlog drains; lag p99 < 5 s under production write load |
| 1. Backfill | `POST /admin/reindex`; nightly reconciliation on | Reconciliation reports 0 missing / 0 stale for a week |
| 2. Shadow | `engine: SHADOW` on the target endpoints | Mismatch rate flat at **zero** for a week, across real production query shapes — not just the synthetic ones |
| 3. Canary | `engine: OPENSEARCH` for a small traffic share via the per-request override | p95 improves, error rate flat, fallback counter ~0 |
| 4. Default | Flip the deployment default; keep MySQL adapter and fallback | Two weeks stable |
| 5. Extend | Next endpoint family; consider CDC only if the outbox is straining | — |

Do not delete the MySQL adapter. It is the fallback, the shadow reference, and the thing that lets you roll back in one config change.

**Effort estimate.** The reference implementation is ~3,500 lines of Java, of which **~1,100 are the integration itself** (outbox, relay, indexer, projection, OpenSearch adapter and translator, index admin); the remainder is the demo domain, the MySQL baseline adapter, the seeder and the benchmark harness — code you already have or do not need. Budget 2–4 weeks for one engineer to take the first endpoint family to production, plus cluster provisioning. Subsequent endpoints are days, not weeks: the pipeline is shared and only the projection and the translator grow.

---

## 11. Open questions for the team

1. **Volume and shape.** Row counts and average document size for the real aggregate, to turn §6.1's formula into a cluster size.
2. **Tenancy.** If the data is multi-tenant, is one index per tenant, a routing key, or a filter the right call? (Default: a filter plus `_routing`; one index per tenant only for very few, very large tenants.)
3. **Which endpoints first.** Rank by (p95 latency × call volume); the reference implementation's benchmark harness can rank them against real query shapes.
4. **Are exact totals contractual** on any endpoint (§9.2)?
5. **Write burst profile.** Peak writes/second and the largest bulk import, to size the relay batch and interval.
6. **Replica for the relay** — is there a read replica the relay's aggregate loads can use?

---

## 12. Reference implementation

Everything above is implemented and runnable in [`app/`](../app); see [`README.md`](../README.md) to start it. The pieces worth reading, in order:

| File | Why |
|---|---|
| `write/ProductWriteService.java` | The two-line write-path footprint |
| `outbox/OutboxRelay.java` | claim → load → index → delete, and why in that order |
| `indexer/ProductIndexer.java` | External versioning; why a 409 is a success |
| `read/ProductAggregateLoader.java` | One loader feeding both hydration and projection |
| `projection/ProductProjectionAssembler.java` | Pure function; unit-testable semantics parity |
| `query/mysql/MySqlProductSearchAdapter.java` | The SQL the schema forces — the problem, in one class |
| `query/opensearch/ProductQueryTranslator.java` | The same semantics as filter clauses |
| `query/ProductSearchRouter.java` | Shadow, fallback, per-request override |
| `indexer/ReindexService.java` | Zero-downtime rebuild |
| `resources/opensearch/product-index.json` | Every data-cost lever from §6.2 |
| `test/…/OutboxToOpenSearchIT.java` | The four properties that must hold, against real containers |

Tests: 3 unit + 4 integration (Testcontainers: MySQL 8.4 + OpenSearch 3.8.0), all green.
