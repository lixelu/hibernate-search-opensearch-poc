# Implementation spec — OpenSearch read path with Hibernate Search

**Audience:** an engineer or agent implementing this in the production data service.
**Assumes:** Java 21, Spring Boot 3.5.x, Hibernate ORM 6.6.x, MySQL 8.x, a denormalised
schema whose complex GET endpoints are slow because filtering and sorting span tables.
**Status:** every instruction here was executed and verified in a reference
implementation at 101,363 products / 250k variants / 376k inventory rows / 402k EAV
attributes. Numbers quoted are measured on that, on one laptop; treat ratios as
transferable and absolutes as indicative.

You do not need the reference project to follow this document, but if you have it, the
file paths named in each step point at working examples.

---

## 0. What you are building

```
POST/PUT/DELETE ──► MySQL (source of truth)
                      │
                      │  Hibernate Search writes an outbox row in the SAME transaction
                      ▼
              hsearch_outbox_event ──► event processor ──► OpenSearch index
                                                                │
GET /products ──► SearchRouter ──► [ MySQL | OpenSearch ] ──────┘
                       │                    │
                       │            ids + sort order only
                       ▼                    │
                  hydrate by primary key ◄──┘
```

Five properties define the design. Preserve all five or the rest stops being safe:

1. **The index answers "which rows, in what order". MySQL still returns what is in
   them.** Search returns ids; the page is hydrated by primary key. The index holds no
   display-only data and no PII, response field values are never stale, and adding a
   field to the API needs no reindex.
2. **The write path never talks to OpenSearch.** It writes MySQL and an outbox row, in
   one transaction. A search outage cannot fail a write.
3. **One query object, several adapters, one router.** MySQL stays implemented and
   becomes the fallback and the shadow reference.
4. **Nothing addresses a concrete index.** Reads and writes go through aliases.
5. **Every claim is measured, not assumed.** A shadow mode that diffs the engines on
   real traffic is part of the deliverable, not an optional extra.

---

## 1. Compatibility — read this before writing any code

These are hard constraints discovered by testing, not preferences.

| Constraint | Consequence |
|---|---|
| **Hibernate Search ships no OpenSearch 3.x dialect.** Verified by inspecting 7.2.6 and 8.4.0: both contain only `OpenSearch1ModelDialect`, `OpenSearch2ModelDialect`, `OpenSearch29ModelDialect`, `OpenSearch214ModelDialect`. | **Choosing Hibernate Search pins you to OpenSearch 2.x.** Use 2.19.x, the final 2.x line. If you must run OpenSearch 3.x, use the `opensearch-java` client directly and hand-roll the pipeline. |
| **Hibernate Search 8.x requires Hibernate ORM 7**, i.e. Spring Boot 4. | On Boot 3.5 use the **7.2.x** line (7.2.6.Final was verified against Boot 3.5.16 / ORM 6.6.53). |
| The `opensearch-java` client 3.x works against a 2.19 cluster. | Verified: full result parity and unchanged latency. You may run both pipelines against one cluster. |

Declare the dialect explicitly (`opensearch:2.19`) rather than letting it be sniffed,
so an upgrade cannot silently change the generated mapping.

---

## 2. Dependencies

```xml
<properties>
  <hibernate-search.version>7.2.6.Final</hibernate-search.version>
</properties>

<dependency>
  <groupId>org.hibernate.search</groupId>
  <artifactId>hibernate-search-mapper-orm</artifactId>
  <version>${hibernate-search.version}</version>
</dependency>
<dependency>
  <groupId>org.hibernate.search</groupId>
  <artifactId>hibernate-search-backend-elasticsearch</artifactId>
  <version>${hibernate-search.version}</version>
</dependency>
<dependency>
  <!-- note: the -coordination- artifact id is relocated; use this one -->
  <groupId>org.hibernate.search</groupId>
  <artifactId>hibernate-search-mapper-orm-outbox-polling</artifactId>
  <version>${hibernate-search.version}</version>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Let Spring Boot manage `hibernate-core` and Micrometer. Do not pin them.

**Acceptance:** `mvn dependency:tree` shows `hibernate-search-* 7.2.6.Final` alongside
`org.hibernate.orm:hibernate-core:6.6.x`, with no version conflict warnings.

---

## 3. Schema: create Hibernate Search's tables with Flyway

`outbox-polling` coordination needs two tables. Under `ddl-auto: none` — which any
Flyway-managed service uses — Hibernate will not create them and startup fails with
`Table 'hsearch_agent' doesn't exist`.

**Do not hand-write the DDL.** Generate it from Hibernate's own metadata so it matches
what the library expects, then check it in:

```bash
# with the entities already annotated (step 5), run once:
--spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create
--spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=/tmp/schema.sql
--spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-source=metadata
```

Then copy the `hsearch_*` statements into a migration. For MySQL 8 with Hibernate
Search 7.2.6 the result is (see `V3__hibernate_search_outbox.sql`):

```sql
CREATE TABLE hsearch_agent (
    id BINARY(16) NOT NULL,
    type ENUM ('EVENT_PROCESSING_DYNAMIC_SHARDING','EVENT_PROCESSING_STATIC_SHARDING','MASS_INDEXING') NOT NULL,
    name VARCHAR(255) NOT NULL,
    expiration DATETIME(6) NOT NULL,
    state ENUM ('RUNNING','SUSPENDED','WAITING') NOT NULL,
    total_shard_count INTEGER NULL,
    assigned_shard_index INTEGER NULL,
    tenant_id VARCHAR(255) NULL,
    payload LONGBLOB NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE hsearch_outbox_event (
    id BINARY(16) NOT NULL,
    entity_name VARCHAR(256) NOT NULL,
    entity_id VARCHAR(256) NOT NULL,
    entity_id_hash INTEGER NOT NULL,
    payload LONGBLOB NOT NULL,
    retries INTEGER NOT NULL,
    process_after DATETIME(6) NOT NULL,
    status ENUM ('ABORTED','PENDING') NOT NULL,
    tenant_id VARCHAR(255) NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE INDEX entityIdHash ON hsearch_outbox_event (entity_id_hash);
CREATE INDEX status ON hsearch_outbox_event (status);
CREATE INDEX processAfter ON hsearch_outbox_event (process_after);
```

Re-generate rather than copy this if you upgrade Hibernate Search.

**Acceptance:** the application starts and `SELECT COUNT(*) FROM hsearch_outbox_event`
returns 0.

---

## 4. Configuration

```yaml
spring:
  jpa:
    open-in-view: false
    properties:
      hibernate.search.enabled: true
      hibernate.search.backend.type: elasticsearch
      hibernate.search.backend.uris: http://opensearch:9200
      hibernate.search.backend.version: opensearch:2.19
      # Demo/dev only. In production use "none" and manage the schema explicitly.
      hibernate.search.schema_management.strategy: create-or-update
      hibernate.search.coordination.strategy: outbox-polling
      hibernate.search.coordination.event_processor.polling_interval: 200
      hibernate.search.coordination.event_processor.batch_size: 200

management:
  endpoints.web.exposure.include: health,info,metrics,prometheus
  metrics.distribution:
    percentiles-histogram:
      catalog.search.request: true
      catalog.search.engine: true
    percentiles:
      catalog.search.request: 0.5,0.95,0.99
      catalog.search.engine: 0.5,0.95,0.99
      catalog.search.hydration: 0.5,0.95,0.99

catalog:
  search:
    engine: MYSQL                     # MYSQL | SHADOW | OPENSEARCH | HIBERNATE_SEARCH
    shadow-candidate: HIBERNATE_SEARCH
    fallback-to-mysql: true
    max-page-size: 100                # raise to your API's real maximum
    hydration-surplus: 5
    max-refill-factor: 4
    opensearch:
      max-from: 10000                 # must not exceed the index max_result_window
      exact-total-hits: true
```

`hibernate.search.enabled: false` is the kill switch that takes the whole library out
of the write path.

**Acceptance:** with `engine: MYSQL` the service behaves exactly as before, and the
index still fills in the background.

---

## 5. Entity mapping — the part that actually matters

### 5.1 The aggregate root

```java
@Entity
@Indexed(index = "hs-products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    // The entity id becomes the document id automatically, but a document id is NOT
    // sortable -- and every sort needs a stable tie-breaker or pages overlap.
    @GenericField(name = "entityId", sortable = Sortable.YES)
    private Long id;

    @FullTextField(analyzer = "standard")
    @KeywordField(name = "name_sort", sortable = Sortable.YES)
    private String name;

    @FullTextField(analyzer = "standard")
    private String description;

    @KeywordField private String brand;
    @KeywordField private String status;

    @GenericField(sortable = Sortable.YES)
    private Instant createdAt;

    @ElementCollection @KeywordField
    private Set<String> tags;
    ...
}
```

### 5.2 To-one associations without an inverse side

```java
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@IndexedEmbedded(includePaths = {"country", "tier"})
@IndexingDependency(reindexOnUpdate = ReindexOnUpdate.SHALLOW)
private Vendor vendor;
```

Without `ReindexOnUpdate.SHALLOW`, bootstrap **fails**: Hibernate Search cannot find
which products to reindex when a vendor changes, because `Vendor` has no
`@OneToMany` back to `Product`. SHALLOW means: reindex when a product is moved to a
different vendor, do **not** reindex when a vendor's own fields are edited.

That is a real semantic decision, and it must be written down: **editing a vendor's
country will not update its products' documents.** Either accept it and handle vendor
edits with a targeted mass reindex, or add the inverse collection and pay for it. Do
not add the inverse collection casually — on a large table it is a million-row
collection on an entity the write model never traverses.

### 5.2.1 Events per user action, and the fan-out from shared parents

Three questions worth answering before this mapping goes anywhere near production, all
measured on 101,363 products.

**Does Hibernate Search write one event per user action?** No — **one per touched entity
instance**:

| User action | Hand-rolled outbox rows | Hibernate Search rows | Which entities |
|---|---|---|---|
| Create a product | 1 | **3** | Product, ProductVariant, Inventory |
| Rename the product | 1 | 1 | Product |
| Reprice a variant | 1 | 1 | ProductVariant |
| Adjust stock | 1 | 1 | Inventory |

The hand-rolled outbox is keyed by aggregate, so it is always one row. Hibernate Search's
is keyed by entity, so a create with four variants and two stock rows each writes 13. They
converge again at processing time — both pipelines coalesce events for the same document
within a batch — so the extra rows cost table volume and poll work, not extra index writes.

**Does a change to a shared parent fan out?** This is the one that bites. With
`ReindexOnUpdate.SHALLOW` (the mapping in §5.2), renaming a category writes **zero**
events in either pipeline:

```
category 3 is referenced by 3,079 products
renaming it (ONE row changed)
  hand-rolled outbox : 0 events
  hibernate search   : 0 events

MySQL now returns 0 products under the old path, 2,747 under the new one.
Both indices still return 2,747 under the OLD path and 0 under the new one.
```

Cheap, and **silently wrong in 2,747 documents**. Nothing alerts, because from the
pipeline's point of view nothing happened.

Give Hibernate Search what it needs to do better — an inverse `@OneToMany` on Category and
`ReindexOnUpdate.DEFAULT` — and the same one-row update becomes:

```
write returned in        105 ms
outbox rows peaked at    3,079      ← one Category event, expanded by the processor
documents indexed        3,079
time until fully drained 5.7 s
```

So: **yes, it fans out, one row to N documents.** Two details matter. The expansion happens
at *processing* time, not write time — the write still returned in 105 ms, and the outbox
absorbs the burst — so the failure mode is a lag spike, not a slow write or an outage. And
the fan-out is unbounded: 3,079 here is one leaf category out of 85. A top-level category
in a real catalogue is millions of documents from a single edit.

**Choose deliberately, and write the choice down:**

1. **`DEFAULT`, letting Hibernate Search resolve the fan-out** (what this project does),
   with an explicit bound on how large a fan-out a single write may trigger. Give Category
   an inverse `@OneToMany` and the library walks it during the writer's session flush.
   Be precise about what that means, because it is easy to assume otherwise: resolution
   happens **in the write transaction**, and it writes **one outbox row per affected
   product**, not one row for the parent. Measured, 3,079 rows in 21 ms. Only the indexing
   itself is deferred to the background.
2. **`SHALLOW` plus a repair job you own.** Not equivalent, and the trade is subtler than it
   looks. There is no id-only enqueue in 7.2, so a hand-driven fan-out loads every affected
   entity to schedule it, and the event processor then loads each one again to index it.
   There *is* a scoped mass indexer, `reindexOnly`, which avoids the outbox entirely and is
   the better of the two repair jobs. Both are measured against each other in 5.2.1c.
3. **Do not denormalise the mutable parent attribute at all.** Index `category.id`, not
   `category.path`, and resolve paths to ids in the application before querying. The
   fan-out problem disappears because nothing in the document depends on the parent's
   mutable state. Still the best answer for a hierarchy that gets reorganised often.

Bound it wherever you land. `DEFAULT` defers the cost but does not cap it, so the guard goes
in the write path: count the affected products first and refuse past
`catalog.indexing.max-parent-fan-out`, because a top-level category is millions of documents
and that is not work for a web request.

The hand-rolled pipeline has the same exposure and less help: it is keyed by product id and
cannot discover a category change at all, so its fan-out has to be written by hand — one
`INSERT … SELECT` into the outbox, which is at least a single statement rather than an
entity load.

### 5.2.1b Checked against the library's own guidance

Two things the annotations' javadoc settles, both of which this project got half right.

**`includePaths` is the fan-out limiter, and naming it changes a default you cannot see.**
`@IndexedEmbedded.includeDepth()` defaults to `-1`, which resolves to
`Integer.MAX_VALUE` — *every field at every level* — when `includePaths` is empty, and to
`0` when it is not. So naming the two fields you want is not merely tidier; it is what stops
the whole associated graph being embedded. Both associations here name their paths, which
is correct:

```java
@IndexedEmbedded(includePaths = {"country", "tier"})   // vendor
@IndexedEmbedded(includePaths = {"path"})              // category
```

`excludePaths` exists as the inverse (incubating in 7.2), and `includeEmbeddedObjectId`
defaults to `false` — worth knowing if you intend to filter on the parent's id.

**`SHALLOW` is not a free trade-off; it comes with an obligation.** From
`ReindexOnUpdate`'s own javadoc, on SHALLOW and NO alike:

> "applications relying on this setting should have periodic batch processes in place to
> refresh the index of affected entities in case nested values changed"

This project had `Product.vendor` mapped SHALLOW and **no such process** — the only
scheduled job is the outbox relay, which is keyed by product id and has never heard of
vendors. That is not a trade-off, it is a staleness bug waiting for the first vendor edit.
Vendor fan-out here is at most 3,014 products and averages 1,689, comfortably inside the
same bound category uses, so vendor is now `DEFAULT` with an inverse collection, exactly
like category. Verified: reclassifying a vendor moved 2,685 active products in MySQL and in
both indices.

The general rule to take away: **`SHALLOW` and `NO` are only honest if you can point at the
job that repairs what they skip.** If you cannot, you have chosen silent staleness rather
than bounded cost.

Both parents are now handled by one `ParentAttributeWriteService`, because the two
pipelines want different things from a parent change and both want them every time:
Hibernate Search resolves its own fan-out, the hand-rolled pipeline needs an explicit
`INSERT … SELECT`, and neither bounds the result — so the bound lives there too.

### 5.2.1c Audited against the 7.2 reference documentation

The whole [7.2 reference](https://docs.hibernate.org/search/7.2/reference/en-US/html_single/),
read against this implementation. Everything below is either a measurement taken on this
project or a quoted rule from the manual with the section number attached.

#### Where the fan-out actually costs you

The manual settles a question that changes where the guard belongs. Section 19.3.2: *"When
a Hibernate ORM session is flushed, Hibernate Search will persist entity change events
within the same Hibernate ORM session and the same transaction."* Reindexing resolution —
walking the inverse association to find which documents embed the changed row — happens
**in the writer's transaction**, not in the background processor. Measured: renaming one
category wrote **3,079 rows** into `hsearch_outbox_event` inside that request.

So the fan-out has two separate costs in two separate places, and only the second one is
asynchronous:

| Where | What happens | Measured on 3,079 products |
|---|---|---|
| The write request | Resolve containing entities, insert one outbox row each | 21 ms |
| The event processor | Reload each entity in a fresh session, index it | 1.6 s |

This is why `catalog.indexing.max-parent-fan-out` guards the **write path**. A parent with
a million children is a million rows written synchronously in a web request, and no amount
of background capacity helps.

#### The two mitigations the manual names, measured separately

Section 19.3.2 is explicit that the background processor *"will completely reload entities
from the database"* with none of the writer's first-level cache, and *"may also need to
load lazy associations to other entities"*. It names two mitigations: batch fetching, and a
second-level cache on the reference entities the reloaded rows point at.

They must be measured **separately**, and on a **fresh JVM**, or the result is not
interpretable. A cache measured after a previous run has already been filled by that run.
Four arms, each on its own JVM, each row the first fan-out that JVM ever performed. Same
category, 3,079 products, every time:

| Arm | SELECTs | Per product | Drain |
|---|---|---|---|
| A. Neither | 15,726 | 5.11 | 5.86 s |
| B. Batch fetching only | 476 | 0.15 | 2.41 s |
| C. Second-level cache only | 15,243 | 4.95 | 5.56 s |
| D. Both | 445 | 0.14 | 2.40 s |

**Batch fetching is the entire effect.** One line of configuration removes 97% of the
queries:

```yaml
hibernate.default_batch_fetch_size: 100
```

**The second-level cache is worth almost nothing here, and it was removed.** On its own it
saved 3% of the queries. Added on top of batch fetching it saved a further 7% and no
measurable time. That does not pay for two jars, a cache provider, and READ_WRITE
invalidation semantics on two entities.

The reason is worth understanding, because it generalises. The event processor works in
transactions of `event_processor.batch_size` events, and within one transaction the
**first-level** cache already holds the parent after the first product loads it. At 200
events per transaction, 3,079 products is about 16 transactions, so a second-level cache
can only save roughly 16 parent loads. That is exactly the size of the gap between arms B
and D. A shared cache cannot help with a fan-out because a fan-out is, by definition,
thousands of rows pointing at the *same* parent.

A full backfill is where a shared cache should finally earn out, and it does not either.
Measured on 101,363 documents:

| | SELECTs | Duration | Cache hits | Cache misses |
|---|---|---|---|---|
| Cache off | 7,695 | 15.0 s | 0 | 0 |
| Cache on, `cacheMode(GET)` | 7,702 | 13.7 s | 0 | 1,014 |

Zero hits. `CacheMode.GET` reads the cache without populating it, so on a freshly started
JVM there is nothing to read and every lookup is a miss. Section 14.4.6 recommends `GET`
*"if many of the entities being indexed refer to a small set of other entities"*, which is
true of this shape, but the recommendation only pays off when application traffic has
already warmed the cache. On a backfill after a deploy, which is when backfills usually
run, it is pure overhead.

**The rule to carry over: measure each of a manual's suggestions on its own.** Bundling
them here would have attributed a 33x improvement to a caching layer that contributed
almost none of it, and the real cause is a single property that costs nothing to maintain.

Cold versus warm is a separate axis and a smaller one. Every arm, including the two with no
cache at all, ran faster on its second fan-out: arm A went from 5.86 s to 3.95 s with no
cache in the picture. That is JIT compilation and the MySQL buffer pool, not caching, and
it applies equally to every arm. The SELECT counts, which is what the comparison actually
rests on, barely move between cold and warm in any arm.

#### Paged fan-outs: the manual has none, and cannot

There is no page size, cursor, or chunk setting for listener-triggered fan-out anywhere in
the 7.2 reference. That is not an omission. Reindexing resolution runs during the writer's
session flush, and the manual describes its cost in the same breath as the reason
`SHALLOW` exists (section 10.10.4): calling the inverse getter *"would lead to loading
thousands of entities into the Hibernate ORM session at once, and would perform badly"*.
One statement changing one parent row is one unbounded unit of work inside a web request.

The manual does discuss paging, but only for batch processes the application writes itself
(section 14.6.4): *"break down the batch process into multiple transactions, each handling
a smaller number of elements"*. So paging a fan-out means owning the fan-out.

#### How to tell you need a manual fan-out

Three questions, in order. The first one that answers "no" is your decision.

1. **Does the fan-out fit in one transaction?** Not "is it fast today" but "is there an
   upper bound". Count the children of the largest parent, and count the children the
   largest parent could plausibly have after a reorganisation. A category at the top of a
   hierarchy is the whole catalogue.
2. **Is the write-path latency acceptable at that size?** Measure the write, not the drain.
   The resolution and the outbox insert are synchronous. Here 3,079 products cost 21 ms to
   163 ms in the request, which is fine; the same shape at a million products is not.
3. **Can the parent attribute leave the document instead?** Indexing `category.id` rather
   than `category.path`, and resolving paths to ids before querying, deletes the problem
   rather than managing it. Ask this before building any of the machinery below.

If the answer is that it does not fit, the association goes to `ReindexOnUpdate.SHALLOW`
and you own the repair. That is the whole meaning of the javadoc's *"applications relying
on this setting should have periodic batch processes in place"*. `SHALLOW` without that job
is not a trade-off, it is silent staleness.

Instrument the decision rather than guessing at it. `catalog.write.fanout` records the size
of every parent change as a distribution, so the p99 of that summary is the number the
guard should be set against, and `catalog.indexing.max-parent-fan-out` refuses anything
past it with a 4xx that names the size.

#### The two ways to do it manually, measured

Both are implemented in `PagedFanOutService`. Same parent, 3,079 products, same machine.

| | Wall time | MySQL SELECTs | Outbox rows | Live indexing |
|---|---|---|---|---|
| Library's own fan-out, for reference | 21–163 ms write, then 1.6–4 s drain | ~476 | 3,079 | unaffected |
| **Scoped mass index** (14.4.5) | 4.3 s | **365** | 0 | **suspended** |
| **Paged enqueue** (14.6.3) | 3.7 s + drain | 3,456 | 3,079 | competes |

**Scoped mass index is the right default.** `massIndexer.type(Product.class).reindexOnly(
"category.id = :id")` streams ids, loads in batches across threads, and writes to the index
directly. Ten times fewer queries than the paged enqueue, and it never touches the outbox.

Two settings on it are not optional. `purgeAllOnStart(false)`, because the manual warns
that *"even if the reindexing is applied on a subset of entities, by default all entities
will be purged at the start"* and *"there is no way to filter the entities that will be
purged"*. Leave that default on and a scoped reindex empties the entire index.
`dropAndCreateSchemaOnStart(false)` for the same reason.

**Its cost is that it stops live indexing.** Section 19.3.6, verified by watching the agent
table through a run: the mass indexer registers as `WAITING`, the event processor moves to
`SUSPENDED`, and it only returns to `RUNNING` when the mass indexer deregisters. Live
indexing stopped for about 4 seconds here. Events are not lost, they queue. For a fan-out
of seconds this is fine. For one of hours it is a freshness outage, and the lag alert will
fire the whole time unless it accounts for a `SUSPENDED` agent.

**Paged enqueue is the gentler option**, and it is gentler precisely because it competes
with live indexing rather than stopping it. It is also restartable and cancellable at page
boundaries. It costs about ten times more queries because every product is loaded twice:
once to enqueue, once again by the event processor to index it.

One thing the manual's own example makes look cheaper than it is. Section 14.6.3 passes
`entityManager.getReference(...)`, an uninitialised proxy, to `addOrUpdate`. That reads as
though ids alone were enough. They are not: measured with the event processor stopped so
nothing else could be loading, 3,079 products produced exactly 3,079 entity loads.
Hibernate Search initialises the proxy. **There is no id-only enqueue in this API**, which
is a real difference from a hand-rolled outbox, where enqueueing a fan-out is one
`INSERT … SELECT` and reads nothing at all.

#### Should fan-outs live in a separate service?

Separate them by **role, not by codebase**. The library already supports this and a second
service would mostly fight it.

The reason is where the cost lands. Resolution and the outbox insert happen in the writer's
transaction, so moving the *processing* elsewhere does not remove the part that is on the
request path. A separate service inherits the whole entity mapping, the same ORM
configuration, and the same schema, and gains a deployment to keep in lockstep. Whenever
those two copies of the mapping drift, the index is silently wrong.

What the manual provides instead, section 19.3.5:

```properties
# web nodes
hibernate.search.coordination.event_processor.enabled = false
# indexing worker nodes
hibernate.search.coordination.event_processor.enabled = true
```

The property exists *"to dedicate some nodes to HTTP request processing and other nodes to
event processing"*. One artifact, one mapping, two roles chosen at deploy time. Worker nodes
can be sized and scaled for indexing, and a slow drain never competes with request threads
for the connection pool.

Two consequences worth planning for:

- **At least one node must process events**, or the backlog grows forever with no error.
  Alert on `catalog.hs.agents{state="RUNNING"}` being zero, not just on lag.
- **Keep dynamic sharding** unless you need deterministic assignment. Static sharding needs
  `shards.total_count` and `shards.assigned` on every node, and *"event processing simply
  won't start until every shard has exactly one node"*, which turns a scaling event into an
  indexing outage.

A genuinely separate service earns its keep in one case: when the fan-out is large enough
that it should not run on the same database connection pool as live traffic, and you want
it to have its own pool, its own limits, and its own failure domain. At that point what you
are building is a reindexing job runner, and the honest version of it drives the scoped
mass index above rather than reimplementing the mapping.

#### Resilience: the failure mode with no symptom

Two paragraphs of the manual describe, together, a way for indexing to fail permanently
and silently.

Section 8.5.1: background failures *"cannot be propagated"* to the caller, and by default
*"the failure is logged at the ERROR level"* and nothing else. Under `outbox-polling`,
**every** indexing operation is a background one.

Section 19.3.8: *"the event processor will try to re-process the events two times, after
that the event will be marked as aborted. Aborted events won't be processed by the
processor."*

Tested by stopping OpenSearch and renaming a category:

- 1,740 events were retried twice and then **all 1,740 were marked ABORTED**, in about 30
  seconds of outage.
- Restoring the cluster changed nothing. The backlog stayed abandoned indefinitely, and
  the index stayed wrong.
- The only trace in the default configuration is a `WARN` per event and an `ERROR` log.

Three things close this, and all three are needed:

1. **A `FailureHandler`** on `hibernate.search.background_failure_handler`, counting into
   `catalog.hs.failures{kind}` and `catalog.hs.failures.entities`. During the outage it
   recorded 9 failures covering 1,740 entities — the leading indicator, roughly 30 seconds
   before the events abort.
2. **A gauge on aborted events**, `catalog.outbox.aborted`. This is the backlog of work the
   library has permanently given up on. It should be flat at zero and any step up is a page.
3. **A recovery path.** `OutboxPollingSearchMapping.reprocessAbortedEvents()`, exposed as
   `POST /api/admin/hs/aborted/reprocess`. Verified: requeued all 1,740 events and the
   index converged, with all three engines then returning the same 1,414 hits.

Without step 3 the only recovery is a full mass index. The sibling
`clearAllAbortedEvents()` discards rather than repairs, so it must always be followed by a
rebuild.

Note the retry budget is a *time* budget, not an attempt budget: three attempts spaced by
`retry_delay` means an outage longer than roughly three delays abandons the entire backlog.
The default delay is 30 s, giving about 90 seconds of tolerance. This project uses 10 s
deliberately, trading a shorter tolerance for faster recovery from brief blips, because the
`reprocess` endpoint makes abandonment recoverable. Choose the pair together.

#### Bulk loads: pause indexing rather than absorb it

Section 14.2.3 documents the indexing plan filter for exactly this: pausing indexing *"when
importing larger amounts of data"*. `POST /api/admin/hs/indexing/pause` excludes
`Object.class`, which excludes every subtype. Import, resume, then mass index.

The constraint matters: the filter **must be application-wide** under `outbox-polling`. The
manual is explicit that session-level filters are unsafe there, because events are processed
in a different session than the one that set the filter, and Hibernate Search throws rather
than let that surprise you. The feature is marked incubating upstream.

#### Configuration audit

Settings whose defaults were wrong for this shape:

| Property | Default | Here | Why |
|---|---|---|---|
| `coordination.event_processor.batch_size` | 50 | 200 | Fewer transactions, warmer persistence context. The manual warns it *"will increase memory usage and in extreme cases may lead to `OutOfMemoryError`s"*; each event here loads a product plus five collections. |
| `coordination.event_processor.retry_delay` | 30 s | 10 s | See the retry budget above. |
| `coordination.entity.mapping.outboxevent.uuid_gen_strategy` | `auto` (random) | `time` | With time-based UUIDs, `event_processor.order=auto` resolves to `id` rather than `time` (19.3.5) — the same ordering guarantee, taken from the primary key instead of a secondary index, which is what removes the deadlock risk once more than one node processes events. The column stays `BINARY(16)`, so no migration. |
| `backend.request_timeout` | *not defined* | 30000 | The manual's wording, not a paraphrase. Unset, a request that never answers holds one of only ten indexing queues open forever. `read_timeout` does not cover connect and send. |
| `background_failure_handler` | log at ERROR | bean | Above. |

Defaults deliberately left alone, with the reasoning recorded so nobody re-opens them:

- **`backend.indexing.queue_count` / `queue_size` / `max_bulk_size`** (10 / 1000 / 100).
  The manual warns that raising these *"incurs a risk of overloading Elasticsearch, leading
  to Elasticsearch giving up on some requests and resulting in indexing failures"*. Indexing
  throughput has never been the bottleneck here; entity reloading was.
- **`backend.thread_pool.size`** (one thread per core). *"As all operations happening in
  this thread-pool are non-blocking, raising its size above the number of processor cores
  available to the JVM will not bring noticeable performance benefits."*
- **Dynamic sharding.** Static sharding requires `shards.total_count` and `shards.assigned`
  on every node, and *"event processing simply won't start until every shard has exactly one
  node"* — a scaling event becomes an indexing outage. Take static sharding only if you need
  deterministic assignment more than you need elasticity.

And one setting that must **not** be set: `indexing.plan.synchronization.strategy`. Section
14.2.2 — with `outbox-polling` *"it does not make sense to set a non-default indexing plan
synchronization strategy, and doing so will lead to an exception on startup."* Indexing is
asynchronous by construction; there is no configuration that makes a write read-your-writes.

#### One operational trap worth knowing

Section 19.3.6: during mass indexing, *"event processing gets suspended while mass indexing
is in progress. Events are still produced and persisted, but their processing gets delayed
until mass indexing finishes."* A backfill therefore stalls live indexing for its whole
duration, and the backlog and lag gauges will climb the entire time. That is expected, not a
fault — but an alert on indexing lag will fire during every backfill unless it is silenced
or the alert accounts for a `SUSPENDED` agent, which `catalog.hs.agents{state}` already
exposes.

### 5.2.2 Observing Hibernate Search's processor

**Hibernate Search ships no metrics.** Verified three ways: not one Micrometer class
across any of its six jars, and neither the
[7.2 reference documentation](https://docs.hibernate.org/search/7.2/reference/en-US/html_single/)
nor the
[8.0 reference documentation](https://docs.hibernate.org/search/8.0/reference/en-US/html_single/)
contains a metrics, monitoring, statistics, JMX or observability section at all. This is
not a gap that closes in the next version — it is simply not part of the library's scope.
(Hibernate Search 5 had a `Statistics` API and JMX beans; both were dropped in 6 and
never replaced.)

Nothing in the library tells you its event processor is alive, how fast it is draining, or
that it just expanded one event into three thousand.

**Hibernate ORM statistics do see the event processor**, and are the only thing that does.
Two steps, and the first one alone does nothing:

```xml
<dependency>
  <groupId>org.hibernate.orm</groupId>
  <artifactId>hibernate-micrometer</artifactId>
</dependency>
```
```yaml
spring.jpa.properties.hibernate.generate_statistics: true
```

Spring Boot's `HibernateMetricsAutoConfiguration` is conditional on that artifact. Setting
the property without it exports **nothing at all**, with no warning — verified by trying
exactly that and getting zero `hibernate_*` meters.

With both in place, one category rename that reindexed 3,079 documents moved the ORM
counters by:

| Counter | Delta | Per document |
|---|---|---|
| `hibernate_entities_loads_total` | 23,075 | **7.5** |
| `hibernate_collections_loads_total` | 14,829 | **4.8** |
| `hibernate_collections_fetches_total` | 14,829 | 4.8 |
| `hibernate_sessions_open_total` | 90 | — |
| `hibernate_transactions_total` | 107 | — |

That is the ORM cost of building one document, measured rather than inferred, and it
explains two earlier findings that were otherwise just numbers: why Hibernate Search's
mass indexer takes ~6x longer than a hand-written batched key lookup (94 s against 15 s
for 101k products), and why it adds ~13 MySQL statements per write against ~2.9 for the
outbox pipeline. It loads an entity graph per document; the other pipeline does five
batched key lookups per *batch*.

**The limitation is real.** These counters are global — they cannot separate the event
processor from ordinary application queries. The ratio against
`catalog_index_writes_total{pipeline="hibernate-search"}` is only clean when indexing
dominates, which is exactly the case during a fan-out. Read it as a diagnostic for
indexing cost, not as a continuous SLI.

#### What was removed, and what it is worth reinstating

Hibernate Search *did* have monitoring, up to and including 5.x: a `Statistics` API and
three JMX MBeans (`StatisticsInfoMBean`, `IndexControlMBean`,
`IndexingProgressMonitorMBean`), switched on with `hibernate.search.generate_statistics`
and `hibernate.search.jmx_enabled`. All of it was dropped in 6 and never replaced. The
statistics it exposed are a good checklist, because they were chosen by people running the
thing:

| Hibernate Search 5 `Statistics` | Equivalent here |
|---|---|
| `getSearchQueryExecutionCount` | `catalog.search.engine` timer count |
| `getSearchQueryTotalTime` / `MaxTime` / `AvgTime` | same timer's sum, max and percentiles |
| `getObjectLoadingTotalTime` / `MaxTime` / `AvgTime` | `catalog.search.hydration` timer |
| `getObjectsLoadedCount` | hydration timer count |
| `getNumberOfIndexedEntities`, `indexedEntitiesCount` | `catalog.index.documents.count{pipeline}` |
| `getIndexSize`, `indexSizes` | `catalog.index.size.bytes{pipeline}` |
| `getSearchQueryExecutionMaxTimeQueryString` | **not reproduced** — see below |

Two observations from doing that comparison. `indexSizes()` was a real gap in what I had
built, and index storage is one of the things a search migration gets judged on, so it is
now a metric. And the slowest-query-string is deliberately not reproduced: capturing query
text belongs in a slow log or a trace, not in a metrics series where it would explode
cardinality — OpenSearch's own slow log is the right home for it.

The more useful observation is what the old API did **not** have: nothing about indexing
throughput, queue depth, or event processing. It predates outbox-polling entirely, so even
if it had survived into 6 it would not have answered the question that actually matters
here — what one user change costs the index.

Everything observable has to be read out of the two tables it keeps and out of the cluster:

| Signal | Source | What it tells you |
|---|---|---|
| Backlog, oldest pending, aborted | `hsearch_outbox_event` | Queue depth and poison events |
| Processors by state, expired leases | `hsearch_agent` | Whether the processor is running, and how work is sharded |
| Documents written | OpenSearch `_stats/indexing` | What it actually did — the only view of its output |

The agent table is the one that is easy to overlook and the one that fails silently. A
processor that dies without deregistering leaves its row behind holding a shard assignment;
until another agent reaps it, that shard's share of the backlog simply stops draining, and
no other signal says why. Export `catalog.hs.agents{state}` and
`catalog.hs.agents.expired` and alert on the latter.

**The lease semantics, measured** (the docs truncate before this section, so these were
established against a running agent):

| Property | Default | Measured behaviour |
|---|---|---|
| `hibernate.search.coordination.event_processor.pulse_interval` | 2000 ms | The agent rewrites its `expiration` roughly every 2 s |
| `hibernate.search.coordination.event_processor.pulse_expiration` | 30000 ms | Each pulse sets `expiration = now + 30 s` |

Verified by setting `pulse_expiration=12000` and watching the lease window fall from
~28.3 s to ~11.4 s. That 30 s default is what makes the expired-agent alert safe to page
on: it cannot fire until a processor has been silent for half a minute, so it is not noisy,
and by the time it fires the backlog really has stopped draining.

**A related operational win:** Hibernate Search validates its own configuration at startup
and warns about keys it did not consume —

```
HSEARCH000568: Invalid configuration passed to Hibernate Search: some properties in the
given configuration are not used. There might be misspelled property keys in your
configuration. Unused properties: [hibernate.search.coordination.event_processor.NOT_A_REAL_PROPERTY]
```

Do not set `hibernate.search.configuration_property_checking.strategy` to `ignore`. A
misspelled Hibernate Search property otherwise does nothing at all, silently, and this
warning is the only thing that catches it.

### 5.3 Derived fields — the reason to use this library

Every reduction the read model needs is a `@Transient` getter with an
`@IndexingDependency(derivedFrom = ...)` declaration naming the persistent properties
it reads. Hibernate Search then reindexes the parent when any of them change, however
many tables away.

```java
/**
 * Ratings as float, NOT the BigDecimal field: Hibernate Search maps BigDecimal to
 * scaled_float, whose descending sort is defective. See pitfall P3.
 */
@Transient
@GenericField(name = "ratingAvg", sortable = Sortable.YES)
@IndexingDependency(derivedFrom = @ObjectPath(@PropertyValue(propertyName = "ratingAvg")))
public float getIndexedRatingAvg() {
    return ratingAvg == null ? 0f : ratingAvg.floatValue();
}

/** Money as an exact integer of minor units, never a float and never scaled_float. */
@Transient
@GenericField(name = "priceMinMinor", sortable = Sortable.YES)
@IndexingDependency(derivedFrom = @ObjectPath({
        @PropertyValue(propertyName = "variants"),
        @PropertyValue(propertyName = "price")}))
public long getPriceMinMinor() { ... }

/** Zero-quantity rows must not count, or the engines disagree on the filter. */
@Transient
@KeywordField(name = "inStockRegions")
@IndexingDependency(derivedFrom = {
        @ObjectPath({@PropertyValue(propertyName = "variants"),
                     @PropertyValue(propertyName = "inventory"),
                     @PropertyValue(propertyName = "region")}),
        @ObjectPath({@PropertyValue(propertyName = "variants"),
                     @PropertyValue(propertyName = "inventory"),
                     @PropertyValue(propertyName = "quantity")})})
public List<String> getInStockRegions() { ... }

/** EAV flattened, so an AND-of-attributes filter is N cheap term clauses. */
@Transient
@KeywordField(name = "attrFlat")
@IndexingDependency(derivedFrom = @ObjectPath(@PropertyValue(propertyName = "attributes")))
public List<String> getAttrFlat() { ... }   // "material=cotton", "fit=slim"
```

For the derived-from paths to work, the associations must be **bidirectional**
(`@OneToMany(mappedBy = ...)` with the matching `@ManyToOne`). Hibernate Search walks
the inverse side to find which documents to reindex.

**Do not use `nested` for the EAV attributes.** A nested field multiplies the Lucene
document count by the number of attributes per product. The flattened `key=value`
keyword array gives the same AND-filtering at a fraction of the cost.

**Acceptance:** start the application and inspect the generated mapping
(`GET /hs-products-000001/_mapping`). Confirm `ratingAvg` is `float`, the money fields
are `long`, and **nothing is `scaled_float`**.

---

## 5.4 If you cannot add an `aggregate_version` column

External versioning exists to stop an older document overwriting a newer one. Four
things can cause that: two workers processing one aggregate concurrently, a redelivery
after a crash, a backfill running against a live index, and a read from a lagging
replica. A monotonic counter on the aggregate root solves all four, but it needs a
column and one line of discipline in every write method. When that is not available,
these are the alternatives, in order of preference.

### Why not simply timestamp the outbox event?

It is the obvious idea and it does not hold, for a reason worth stating precisely:

> **The version must describe the state that was read, not the event that triggered the
> read.**

With a key-only outbox the relay claims an event, then reads *current* state — which may
already include changes the event knows nothing about, and may still be missing changes
whose events were recorded earlier but committed later. Event time and state freshness
come apart, and once they do, `external_gte` starts rejecting documents that are actually
newer.

The concrete failure needs only a slow transaction:

| time | what happens |
|---|---|
| 10:00:02 | W3 inserts its outbox row, `occurred_at = 10:00:02`. Its transaction stays open. |
| 10:00:03 | W4 commits; outbox row `occurred_at = 10:00:03`. |
| 10:00:04 | Relay processes W4, reads state — W3 is not committed yet, so it is invisible. Indexes at version 10:00:03. |
| 10:00:05 | W3 commits. |
| 10:00:06 | Relay processes W3, reads state, builds the correct document containing both changes — and stamps it 10:00:02. **Rejected as stale.** |

No further event exists, so the index stays permanently missing W3. The same shape
appears with clock skew across writers, and with any coalescing that picks an event time
rather than a read time. Auto-increment ids fail identically, and for the identical
reason: both are assigned when the row is *inserted*, not when it becomes *visible*.

Contrast the counter: the relay takes `aggregate_version` from the row it just loaded, so
version and state come from one snapshot by construction. Whichever worker read the newer
state necessarily holds the higher version. That is the property to preserve, and the two
options below preserve it in different ways.

### Option A — the read timestamp (implemented, recommended when no counter is available)

`CURRENT_TIMESTAMP(6)` taken from the database inside the same query that loads the
aggregate, as epoch microseconds. Set
`catalog.indexing.version-source: READ_TIMESTAMP`.

It works because of one property of READ COMMITTED: **a read that happens later sees a
superset of what an earlier read saw.** So a higher read timestamp always means a state
at least as new — exactly the invariant `external_gte` needs, and exactly the invariant
an event timestamp lacks.

Its advantage over Option B is that it does not read the data at all, so nothing in the
data can move it backwards. Re-running the child-delete scenario that corrupts Option B:

```
product indexed           version=1788977048599126  inStockRegions=[EU, US]
bumped the US stock row   version=1788977050083591  inStockRegions=[EU, US]
deleted the US stock row
relay batch               {"claimed":1,"indexed":1,"deleted":0,"stale":0,"failed":0}
index now says            version=1788977050248866  inStockRegions=[EU]
MySQL truth               inStockRegions=EU
index and MySQL agree?    True
```

Three requirements, all easy to state and easy to get wrong:

1. **One clock.** Take it from the database, never from the application server. Several
   app instances with drifting clocks reintroduce exactly the problem this avoids.
2. **Read from the primary.** A relay reading a lagging replica gets a late timestamp
   with early state, which inverts the invariant.
3. **A clock that does not step backwards.** NTP steps and failover both can.

One consequence to plan for: the version no longer corresponds to anything in MySQL, so
version-based reconciliation changes shape. What it still supports is a lower bound — the
relay must have read the row after the row was last written, so a document whose version
predates `product.updated_at` is provably built from stale state. `ReconciliationService`
implements that, and stops reporting "ahead" as a signal under this source, because being
ahead is the normal case.

### Option B — derive the version from `updated_at`

`GREATEST(updated_at)` across the aggregate's timestamped tables, as epoch
milliseconds, handed to OpenSearch as the external version. No new column, no
write-path change. Set `catalog.indexing.version-source: MAX_UPDATED_AT`. Prefer Option A to this.

```sql
CAST(UNIX_TIMESTAMP(GREATEST(
    p.updated_at,
    COALESCE((SELECT MAX(i.updated_at) FROM inventory i
                JOIN product_variant pv ON pv.id = i.variant_id
               WHERE pv.product_id = p.id), p.updated_at)
)) * 1000 AS UNSIGNED) AS derived_version
```

**What each source can actually see.** Measured by making the same changes with raw
SQL, bypassing the application entirely:

| Change, made in raw SQL | `aggregate_version` | derived `max(updated_at)` |
|---|---|---|
| `UPDATE product SET name = …` | blind | **moved** |
| `UPDATE product_variant SET price = …` | blind | **blind** — that table has no `updated_at` |
| `UPDATE inventory SET quantity = …` | blind | **moved** |

That table is the whole decision. The counter sees only what application code bumps.
The timestamp sees any write to a table carrying `updated_at ON UPDATE
CURRENT_TIMESTAMP` — **including writes that bypass the service** — but is blind to
tables that lack the column.

**The failure mode that disqualifies it for most schemas.** `MAX(updated_at)` is not
monotonic under **child-row deletes**. Delete the most recently updated child row and the
maximum drops back to the next-newest, so the corrected document carries a *lower*
version than the one already indexed. Measured end to end through the pipeline:

```
product indexed          version=1788976026960  inStockRegions=[EU, US]
bumped the US stock row  version=1788976028709  inStockRegions=[EU, US]   (+1.7s)
deleted the US stock row derived version is now 1788976026958  (-1.8s)
relay batch              {"claimed":1,"aggregates":1,"indexed":0,"deleted":0,"stale":1,"failed":0}
index now says           version=1788976028709  inStockRegions=[EU, US]
MySQL truth              inStockRegions=EU
index and MySQL agree?   False
```

The relay built the correct document, OpenSearch refused it as stale, and the index now
advertises stock that does not exist — **permanently**, until some later change pushes
the version past the old value. Note `"failed": 0`: the relay reported success, so
nothing alerts. Only the `stale` counter moved, and in normal operation that counter is
benign.

Two consequences:

- **Only choose Option B where child rows are soft-deleted** — a soft delete is an
  UPDATE, so the timestamp moves forward and monotonicity holds — or where child rows are
  never deleted at all.
- **Reconciliation must flag an index that is *ahead*, not only one that is behind.**
  Under a counter, `indexed > expected` is a benign race. Under a timestamp it is the
  signature of exactly this corruption. `ReconciliationService` now reports `ahead`
  separately, and reads whichever version expression the configured source publishes.

Three more things to check before choosing it:

1. **Resolution.** `DATETIME` with no fractional digits gives one-second granularity, so
   two writes in the same second are indistinguishable. Use `DATETIME(3)` or `(6)`.
   With `external_gte`, equal versions are accepted, so a collision means last-writer-wins
   rather than a rejection — safe, but not ordered.
2. **Coverage.** Every table whose changes must be ordered needs the column, and needs
   it in the `GREATEST`. Adding `updated_at` to a child table is a smaller migration than
   adding a counter and the discipline to bump it, so this is often the cheaper path to
   the same guarantee.
3. **Clock.** It is the database server's clock. Fine on a single primary; on failover or
   multi-primary, a backwards step can reject good writes until the clock catches up.

**Does a child update require touching the parent row?** No — that is the point of the
`GREATEST`. Measured: updating an inventory row moved the derived version forward by 382
seconds while `product.updated_at` did not change at all. But note the separation of
concerns this hides: the version answers *which write is newer*, not *that a write
happened*. Something still has to enqueue the reindex — the outbox row, or Hibernate
Search's `@IndexingDependency`. A raw SQL update to a child table moves the version and
still never gets indexed, because nothing told the pipeline to look.

### Option C — the binlog coordinate, if you are using CDC

If changes arrive through Debezium, each event carries its position in the binary log,
which is globally monotonic at the source — including for deletes, which is exactly where
Option B breaks. Pack the binlog file number and position into
a long, or use the GTID sequence, and use that as the external version. No schema
dependency at all, and it orders every change from every writer. This is the strongest
option, and it is free once CDC is in place — another reason CDC gets more attractive
the moment a broker already exists.

### Option D — remove the need for a version by serialising per aggregate

The concurrency hazard only exists because two workers can process the same aggregate at
once. Partition the outbox by `aggregate_id % N` and give each partition to one worker,
and events for one aggregate are processed one at a time, in order. This is what
Hibernate Search's agent registry does, and what a broker's key-based partitioning gives
you for free.

That removes the concurrency case but not the other three, so pair it with:

- **backfill into a fresh index and alias-swap**, never into the live one; and
- **read the aggregate from the primary**, not from a replica.

With those two rules, a version is not needed for correctness. What you give up is the
self-healing property: replay is no longer harmless by construction, it is harmless
because of an operational rule someone has to keep.

### Option E — optimistic concurrency with `if_seq_no` / `if_primary_term`

Read the document's `_seq_no` and `_primary_term`, write conditionally, and on a
conflict re-read the aggregate and retry. Correct, and it needs nothing from the schema.
The cost is a read before every write and a retry loop, which is precisely what bulk
indexing exists to avoid. Reasonable at low write rates; do not build a high-throughput
relay on it.

### What you lose without any monotonic version

Be explicit about this, because it is easy to discover late: no version means a backfill
cannot safely run against a live index, a lagging-replica read can overwrite a fresher
document, and at-least-once redelivery stops being harmless. Each has a workaround above,
but they are operational rules rather than properties the system enforces for you.

---

## 5.5 What the pipeline costs MySQL, measured

Same workload, three configurations, differencing `SHOW GLOBAL STATUS`.

Two instrumentation traps first, because both cost me a measurement:
`performance_schema.global_status` omits the `Com_*` statement counters and reports zero
traffic; and this application uses server-side prepared statements, whose executions do
not appear in `events_statements_summary_by_digest` at all — a digest-based measurement
silently sees nothing. `SHOW GLOBAL STATUS` counts everything.

**Idle, 60 seconds, zero traffic** — what the pollers cost by existing:

| Configuration | statements | transactions | rows updated |
|---|---|---|---|
| Indexing off | 1 | 0 | 0 |
| Hand-rolled relay only | 571 | 285 | 0 |
| Both pipelines | 1,421 | 626 | 28 |

The relay's ~570 a minute is its 200 ms poll: 300 polls, each a `SELECT … SKIP LOCKED`
and a commit. Hibernate Search adds ~850 more, part polling and part its agent-registry
heartbeat — those 28 row updates.

**Per write, 200-write burst**, both pipelines drained before the second snapshot so the
whole indexing cost is inside the window:

| Per write | Indexing off | Hand-rolled only | Both |
|---|---|---|---|
| statements | 16.9 | 19.8 | 32.8 |
| transactions | 1.0 | 1.1 | 2.0 |
| InnoDB rows read | 5.8 | 9.3 | 17.4 |
| rows inserted | 1.0 | 2.0 | 3.5 |
| rows deleted | 1.0 | 2.0 | 3.5 |
| index lookups | 5.9 | 7.4 | 14.3 |

The hand-rolled outbox adds **~2.9 statements per write** (+17%): the outbox row, its
acknowledgement, and the projection load. Hibernate Search adds **~13** (+66%), about
**4.5x** more, for three structural reasons — one outbox row per touched entity rather
than per aggregate, aggregates loaded through the ORM rather than in batched key lookups,
and the agent heartbeat.

Set that against what the read path stops doing — a join-and-sort plus its `COUNT(*)`
twin, 200–1600 ms each — and the net effect on the primary is still strongly negative
once read traffic moves. But size the write side for it, and note that if you run only
one pipeline, which one you choose is worth ~13 statements per write.

Re-run it on your own write mix: `make db-pressure`, or the scripts in `scripts/`.

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


## 6. The search seam

Define one engine-independent query object and one port. Both are small and both are
what make everything after this reversible.

```java
public record ProductQuery(String text, List<String> statuses, ..., int page, int size,
                           Integer windowOffset) {
    // windowOffset overrides page*size. Null in the normal case; set only when the
    // read service widens the window (see 7). Deriving the offset from a widened size
    // silently skips rows -- see pitfall P8.
    public int offset() { return windowOffset != null ? windowOffset : page * size; }
    public ProductQuery withWindow(int newSize) { /* same offset, larger size */ }
}

public record SearchSlice(List<Long> ids, long totalHits, boolean totalIsLowerBound,
                          String engine, long tookMillis) { }

public interface ProductSearchPort {
    SearchSlice search(ProductQuery query);
    String engine();
}
```

The Hibernate Search adapter (`HibernateSearchProductAdapter.java`):

```java
@Transactional(readOnly = true)
public SearchSlice search(ProductQuery query) {
    SearchResult<Long> result = Search.session(entityManager).search(Product.class)
        .select(f -> f.id(Long.class))          // ids only: no entity loading
        .where(f -> f.bool(bool -> {
            bool.must(f.matchAll());
            if (query.hasText()) {
                bool.must(f.match().fields("name", "description").matching(query.text()));
            }
            // everything else in FILTER context: no scoring, cacheable
            bool.filter(f.terms().field("status").matchingAny(query.statuses()));
            bool.filter(f.range().field("priceMaxMinor").atLeast(minorUnits(query.priceMin())));
            bool.filter(f.range().field("priceMinMinor").atMost(minorUnits(query.priceMax())));
            bool.filter(f.prefix().field("category.path").matching(query.categoryPathPrefix()));
            for (var attribute : query.attributes().entrySet()) {
                bool.filter(f.match().field("attrFlat")
                        .matching(attribute.getKey() + "=" + attribute.getValue()));
            }
        }))
        .sort(f -> f.field("priceMinMinor").order(order).then().field("entityId").asc())
        .fetch(query.offset(), query.size());

    return new SearchSlice(result.hits(), result.total().hitCount(), false, engine(), took);
}
```

Three rules:

- **`@Transactional(readOnly = true)`** — `Search.session(entityManager)` needs an open
  session, and `open-in-view` is off.
- **Filter context for everything except free text.** This is worth a large fraction of
  the latency win.
- **Every sort ends with `entityId`.** Without a tie-breaker, equal-valued documents
  swap between pages, and `search_after` has no cursor.

Keep the MySQL adapter. It is the fallback, the shadow reference, and the rollback.

---

## 7. Read service: search, then hydrate

```java
int want = query.size();
int window = want + hydrationSurplus;          // default 5
do {
    slice = router.search(query.withWindow(window), override);
    hydrated = loader.loadInOrder(slice.ids());  // batched PK lookups
    dropped = slice.ids().size() - hydrated.size();
    if (hydrated.size() >= want || slice.ids().size() < window || window >= maxWindow) break;
    window = Math.min(maxWindow, Math.max(window * 2, want + dropped * 2));
} while (true);
items = hydrated.subList(0, min(want, hydrated.size()));
```

**Why the surplus and the refill exist.** An asynchronous index can name a row MySQL no
longer has — deleted, or changed so it no longer matches — for as long as the processor
is behind. Hydration finds nothing, and dropping it silently returns 19 rows where 20
were asked for. A fixed surplus is not enough on its own: a bulk delete can strand more
stale ids on one page than any constant covers, so the window widens and retries,
bounded. Report the drop count in the response and as a metric; a rising rate means the
index is behind and must be visible rather than absorbed.

**Hydration at your scale.** It stays proportional to the *page*, not the result set:
20 keys, a handful of index seeks each. Table cardinality enters only through B-tree
depth. The risk at hundreds of millions of rows is buffer-pool residency, not depth —
cold pages become random reads. Three mitigations, in order: hydrate a **narrow list
projection** (not the full aggregate) for list endpoints, behind a covering index; cap
or paginate child collections in list responses, because hydration cost scales with
fan-out; and if it still measures badly on your hottest endpoint, put that list's
display fields in the document and skip the hop **for that endpoint only**.

---

## 8. Router: the migration switch

```java
switch (engine) {
    case MYSQL            -> timed(mysql, query);
    case OPENSEARCH       -> withFallback(opensearch, query);
    case HIBERNATE_SEARCH -> withFallback(hibernateSearch, query);
    case SHADOW           -> shadow(query);   // MySQL answers; candidate compared off-path
}
```

- **Fallback:** any candidate error falls back to MySQL and increments a counter.
  Fallback is only tolerable because the adapters return ordered ids — the fallback
  answer is *correct*, just slower.
- **Do not fall back on a deep-paging rejection.** That is a client error, not an
  availability failure; falling back hides a misconfiguration behind a very slow
  success.
- **Shadow:** serve MySQL, run the candidate on a virtual thread off the response path,
  compare id lists and totals, emit `catalog.search.shadow{candidate,match}`. Expect a
  small noise floor from writes landing between the two calls — judge the pattern, not
  a literal zero.
- **Per-request `?engine=` override** makes canarying a routing decision.

---

## 9. Metrics

Register every gauge in one class so the catalogue is readable. Counters and timers
stay at their call sites.

| Metric | Type | Answers |
|---|---|---|
| `catalog.search.request{engine,outcome}` | timer | What the caller experiences, hydration included |
| `catalog.search.engine{engine,outcome}` | timer | Per-engine latency and error rate |
| `catalog.search.hydration{engine}` | timer | What the second hop costs |
| `catalog.search.shadow{candidate,match}` | counter | **Do the engines agree on real traffic?** The cutover gate |
| `catalog.search.fallback{engine}` | counter | How often the cluster is failing us |
| `catalog.search.stale_dropped{engine}` | counter | Index naming rows MySQL no longer has |
| `catalog.search.refill{engine}` | counter | Pages short enough to need a second search |
| `catalog.search.deep_paging_rejected{engine}` | counter | Callers hitting the paging ceiling |
| `catalog.outbox.backlog{pipeline}` | gauge | Queued indexing work |
| `catalog.outbox.lag.seconds{pipeline}` | gauge | **How stale is the index? Alert on this one.** |
| `catalog.outbox.aborted{pipeline}` | gauge | Poison rows nobody is retrying |
| `catalog.index.bulk{target}` | timer | Bulk request duration |
| `catalog.index.documents{outcome}` | counter | indexed / deleted / stale / failed |
| `catalog.index.documents.count{pipeline}` | gauge | Documents in the index |
| `catalog.db.rows{table}` | gauge | The denominator for the line above |

Boot's actuator adds `http.server.requests`, `hikaricp.*` and the JVM meters; do not
duplicate them.

**Health must not lie in either direction.** A search outage degrades latency, not
correctness: reads fall back to MySQL and writes never touched the cluster. An instance
in that state is serving correct responses and must keep receiving traffic. So report a
custom `DEGRADED` status mapped to HTTP 200, and define the readiness group to include
only what the instance needs in order to answer *correctly*:

```yaml
management.endpoint.health:
  probes.enabled: true
  group:
    readiness.include: readinessState, db     # deliberately NOT search
  status:
    order: DOWN, OUT_OF_SERVICE, DEGRADED, UNKNOWN, UP
    http-mapping.DEGRADED: 200
```

Verified: with the cluster paused, `/actuator/health` reports `DEGRADED` at HTTP 200,
`/actuator/health/readiness` stays `UP`, and reads keep succeeding from MySQL.

**Make amplification measurable, not just indexing volume.** Indexing work is
uninterpretable on its own — 3,000 documents a second is either healthy or an incident
depending on what asked for it. Pair every indexing counter with a counter of user-level
changes, and put the ratio on the dashboard:

```promql
sum by (pipeline) (increase(catalog_index_writes_total[5m]))
  / on() group_left() clamp_min(sum(increase(catalog_write_mutations_total[5m])), 1)
```

Three things this got wrong first time, all worth copying the fix for:

- Use `increase()` over a window, not `rate()`. A rate over a single event collapses the
  denominator and the ratio becomes meaningless — the first version reported 56,000
  documents per change for what was really about 31.
- `on() group_left()` is required. Dividing a vector that has a `pipeline` label by one
  that has no labels matches nothing and the panel silently shows "No data".
- Take the numerator from the **cluster's** own `index_total`, not from application code.
  It is the only way to see what Hibernate Search writes, since the library does its own
  indexing and reports nothing you can meter.

**A gauge can miss the event entirely.** A 3,079-document fan-out drains in about a
second; at any realistic scrape interval the backlog gauge may never sample it. Record
fan-out size as a `DistributionSummary` at the point it happens, so the event is captured
regardless of when Prometheus looks.

**Two rules for the gauges:**

1. **Cache them.** Each costs a database or cluster round trip, and Prometheus scrapes
   every node. A 10-second TTL keeps them honest without letting the scrape interval
   drive query volume. Swallow refresh failures — a database blip must not break the
   scrape that would have told you about the database blip.
2. **Hold a strong reference to the gauge's state object** (see pitfall P5).

Suggested alerts: `catalog.outbox.lag.seconds` p99 > 60s; any `catalog.index.documents{outcome="failed"}`;
any sustained `catalog.search.fallback`; any `catalog.search.shadow{match="false"}` during
the shadow phase; `catalog.index.documents.count` diverging from `catalog.db.rows`.

---

## 10. Operations you will need at 3am

Build these before you need them:

| Operation | Hibernate Search | Notes |
|---|---|---|
| Backfill | `Search.session(em).massIndexer(Product.class).startAndWait()` | **Purges first**, so the index is incomplete while it runs. Run against a non-live index or accept the window. |
| Backlog / lag | `SELECT COUNT(*)`, `MIN(process_after)` on `hsearch_outbox_event` | Compute the age **in SQL** (pitfall P6) |
| Poison rows | `WHERE status = 'ABORTED'` | The library stops retrying and leaves them visible |
| Targeted repair | `Search.session(em).indexingPlan().addOrUpdate(entity)` | For vendor-edit style changes that SHALLOW does not propagate |
| Drift detection | see below | The library does not provide this |

**Drift detection is yours to build**, and the sampled approach does not scale. Use a
**range checksum**: partition the primary key into fixed ranges and compare, per range,

```sql
SELECT COUNT(*), SUM(aggregate_version) FROM product WHERE id BETWEEN ? AND ?
```

against the same range in OpenSearch (`range` filter + `value_count` + `sum`). Equal
pairs mean the range agrees on membership *and* freshness. Cost is proportional to
ranges, not rows; it detects orphans, misses and staleness in one pass; and it tells you
*which* range to replay. Run it continuously at a low rate, not nightly in one burst.
This requires a monotonic version column on the aggregate that every write bumps.

---

## 11. Tests that must exist

Four integration tests against real MySQL and real OpenSearch (Testcontainers), all
verified working in the reference implementation:

1. **A write reaches the index with no indexing code on the write path.**
2. **A child-table write reindexes the parent document.** Reprice a variant; assert the
   parent moves between price windows. This is the test that proves
   `@IndexingDependency` is declared correctly, and it is the one that will fail when
   someone adds a new derived field and forgets it.
3. **A delete removes the document.**
4. **Both engines return identical ids for the same query.**

Plus unit tests for anything computed: the derived getters, the version sources, and the
window/offset arithmetic in `ProductQuery`.

### And an end-to-end suite that drives the running service

Integration tests with their own containers prove the code works. They do not prove the
*deployment* works — the engine that is actually selected, the real relay interval, the
real cluster, the real page size cap. Add a second suite that talks HTTP to a running
instance and can be pointed at any environment:

```bash
mvn verify -Pe2e                                        # localhost
mvn verify -Pe2e -De2e.baseUrl=https://staging.internal  # anywhere
```

Four things it must establish, each across **every** read path:

1. **Write correctness**, read back through an endpoint that never touches an index. This
   separation is what makes the rest interpretable: if writes are correct and the index
   disagrees, the pipeline is at fault, and not otherwise.
2. **Propagation**, for every kind of write — including one two tables from the document
   (a variant reprice) and one three tables away (a stock change). Measure the interval;
   that number, not query latency, is what decides whether an asynchronous read model is
   acceptable to the product.
3. **Result parity with the baseline** over a matrix of query shapes. This is the cutover
   gate, and it is the check that catches an engine that is fast and wrong.
4. **Latency**, p50/p95/p99 per scenario per engine, written to a report a human reads.

Three details that turned out to matter:

- **Tag every test's data with a unique run id.** Runs overlap, and rows survive. An
  assertion scoped to a marker word cannot be made to lie by leftover data.
- **An empty result is not proof a delete propagated.** After a delete the document can
  still be in the index while hydration silently drops the dead id — so a test that only
  checks for an empty page passes even when deletes never propagate at all. Require the
  reported stale-drop count to be zero as well. Getting this wrong made deletes look 100x
  faster than every other operation, which is what gave it away.
- **Keep the suite out of the ordinary build.** It needs a running service; wire it to a
  profile and exclude its tag from surefire, or CI fails for the wrong reason.

Scope every assertion to a **unique marker word** per test. Tests sharing one database
pollute each other's result sets, and an absolute assertion like
`assertThat(backlog()).isEqualTo(1)` will fail the moment a second test class exists
(pitfall P9).

---

## 12. Rollout

| Phase | Exit criterion |
|---|---|
| 0. Deploy with `engine: MYSQL` | Backlog drains; `catalog.outbox.lag.seconds` p99 < 5s at production write load |
| 1. Mass index | Range-checksum reconciliation clean for a week |
| 2. `engine: SHADOW` | `catalog.search.shadow{match="false"}` flat at the noise floor for a week, on **real** query shapes |
| 3. Canary via `?engine=` | p95 improves, error rate flat, fallback counter ~0 |
| 4. Flip the default | Two weeks stable |

Never delete the MySQL adapter.

---

## 13. Pitfalls — each of these was hit and cost real time

| # | Pitfall | Symptom | Fix |
|---|---|---|---|
| **P1** | Hibernate Search has no OpenSearch 3 dialect | Backend version check fails at startup | Pin OpenSearch **2.19.x**, or drop Hibernate Search |
| **P2** | Hibernate Search 8.x needs ORM 7 | Dependency conflict on Boot 3.5 | Use the **7.2.x** line |
| **P3** | **`BigDecimal` maps to `scaled_float`**, whose *descending* sort silently returns non-maximal documents | Sorting by price descending returns the wrong rows; a `max` aggregation on the same field disagrees with the sort | Never index a sortable field as `scaled_float`. Money → `long` minor units, ratings → `float`. Reproduced on OpenSearch **2.19.6 and 3.8.0**; ascending sort is fine; adding a `range` filter masks it, which is why casual testing misses it |
| **P4** | `ddl-auto: none` + outbox-polling | `Table 'hsearch_agent' doesn't exist` | Generate the DDL from Hibernate metadata, check it into Flyway (§3) |
| **P5** | **Micrometer holds gauge state by weak reference** | Every gauge reports `NaN`, with no error anywhere | Keep a strong reference (e.g. a `List<Cached>` field) to each gauge's state object |
| **P6** | `getTimestamp()` reinterprets in the JVM's zone | Lag metric off by the UTC offset — silently, in the metric you rely on | Compute the age in SQL: `TIMESTAMPDIFF(SECOND, MIN(...), CURRENT_TIMESTAMP(3))` |
| **P7** | Clamping an out-of-range `from` | Caller asks for row 100,000 and is served row 10,000 — a wrong answer that looks right | Reject with `400`, naming the requested offset and the limit |
| **P8** | Widening the search window when offset derives from `page * size` | Page 1 of size 20 begins at row 25; rows 20-24 are returned to nobody. Invisible at page 0, which is where tests usually look | Carry an explicit window offset (§6), and unit-test it |
| **P9** | `@Container` on a shared test base class | Containers stop after the first test class; the second fails with 30s connection timeouts | Start singleton containers in a `static {}` block; drop `@Testcontainers` |
| **P10** | Two integration tests sharing one database | Absolute assertions fail once a second test class exists | Unique marker word per test; assert relative deltas |
| **P11** | To-one `@IndexedEmbedded` without an inverse side | Bootstrap fails | `@IndexingDependency(reindexOnUpdate = SHALLOW)`, and document what will not propagate |
| **P12** | Mass indexer is slower than a keyset backfill | Rebuild takes far longer than expected | Measured **94s vs 15s** for 101k products. The mass indexer loads through the ORM; a hand-rolled backfill does batched key lookups. Budget accordingly |
| **P13** | `--` inside an XML comment | `ModelParseException` on the pom | Trivial, but it will happen |
| **P14** | **Spring Boot auto-configures a second, unused Elasticsearch client** | The service works perfectly and reports `DOWN`; readiness fails and the instance is pulled from the load balancer | Hibernate Search's backend pulls in `elasticsearch-rest-client` and its sniffer transitively. Boot builds a `RestClient` from `spring.elasticsearch.uris` plus a health indicator for it, and the sniffer rewrites the node list to the cluster's *published* address — unreachable from outside the container network. Exclude `ElasticsearchRestClientAutoConfiguration` and `ElasticsearchRestHealthContributorAutoConfiguration` |
| **P15** | Health status order configured at `management.health.status.*` | A custom status is silently unranked, the aggregate reports `UP`, and behaviour that looks right is an accident | The properties live under `management.endpoint.health.status.*`. Referencing `readinessState` in a group also needs `management.endpoint.health.probes.enabled: true`, or startup fails |

---

## 14. Measured results from the reference implementation

101,363 products / 250k variants / 376k inventory / 402k EAV attributes. MySQL 8.4 with
a 1 GB buffer pool and a `FULLTEXT` index; OpenSearch 2.19.6 single node, 1 GB heap;
everything on one laptop. 12 iterations after 3 warm-ups; engine call only.

| Scenario | MySQL p50 | OpenSearch (hand-rolled) | Hibernate Search | Same ids as MySQL |
|---|---|---|---|---|
| status page by date | 263 ms | 6 ms (43.8x) | 10 ms (26.3x) | both ✔ |
| vendor country, sort by price | 51 ms | 7 ms (7.3x) | 6 ms (8.5x) | both ✔ |
| category + tags + in-stock | 219 ms | 8 ms (27.4x) | 8 ms (27.4x) | both ✔ |
| EAV attributes + price window | 1652 ms | 6 ms (275x) | 4 ms (413x) | both ✔ |
| full text + filters | 76 ms | 7 ms (10.9x) | 6 ms (12.7x) | both ✔ |
| everything at once | 234 ms | 7 ms (33.4x) | 6 ms (39.0x) | both ✔ |
| deep page (offset 2000) | 252 ms | 9 ms (28.0x) | 16 ms (15.8x) | both ✔ |

Other measurements worth carrying over:

- **Storage:** hand-rolled index 19.2 MB, Hibernate Search index 20.2 MB, for the same
  101,363 documents — against ~207 MB for the same data in MySQL. Hibernate Search sets
  `doc_values: false` on non-sortable keywords by itself, which is why the two are close.
- **Write-path cost:** one outbox INSERT is inside run-to-run noise on POST, ≤0.2 ms on
  a small PUT.
- **Propagation latency:** create visible in ~0.5-0.9 s; a variant reprice reaches the
  parent document in ~1.3 s on both pipelines.
- **Outbox write amplification differs.** For one product create (product + 1 variant +
  1 inventory row), the hand-rolled outbox wrote **1** row keyed by aggregate id;
  Hibernate Search wrote **3**, one per touched entity instance (`Product`,
  `ProductVariant`, `Inventory`). They coalesce to one document at processing time, but
  the row volume scales with entities touched, not aggregates changed. At four variants
  with two stock rows each that is 13 rows per create. Size the table and the poll
  accordingly.
- **Exact counts are cheap.** Counting every match instead of capping at 10,000 cost
  +0.1 ms at 101k matching documents. Default to exact; re-measure at your cardinality,
  because the cost is linear in matched documents.
- **Substring filters are the weak case.** A leading wildcard scans the whole term
  dictionary, so cost tracks *distinct terms*, not documents: 9 ms at 100k terms, 43 ms
  at 1M. Against MySQL's `LIKE '%...%'` it is ~8x rather than the 20-150x of term
  filters. If you need true substring matching, add a trigram sub-field (2 ms vs 17 ms,
  for ~35% more index on that field) rather than leaving it as a wildcard.

---

## 15. What you must determine in the real project

I could not answer these from the reference implementation:

1. **Do writes bypass Hibernate?** Native SQL, `JdbcTemplate` batch jobs, bulk imports,
   another service on the schema, a DBA. Hibernate Search sees none of them. This is the
   single most important question, and if the answer is yes, you need either CDC
   (Debezium reads the binlog and sees every change regardless of origin) or a
   reconciliation job you actually trust.
2. **Your real cardinality and shard count.** Deep paging cost multiplies by shard count;
   substring cost tracks distinct terms. Both need measuring on your data.
3. **Does any endpoint contractually require transactionally-consistent counts?** An
   exact count from an async index is exact *as of what the index has seen*. If that is
   not good enough, that endpoint stays on MySQL.
4. **Your paging contract.** If callers page to `from=100000`, raise both `max-from` and
   the index `max_result_window` together, and measure at your shard count.
5. **Do vendor/category-style edits need to propagate?** SHALLOW says they do not
   (§5.2). Decide deliberately.
