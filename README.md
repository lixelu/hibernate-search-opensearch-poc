# Catalog Data Service — MySQL write model, OpenSearch read path

A runnable reference implementation for the proposal in
**[docs/opensearch-integration-spec.md](docs/opensearch-integration-spec.md)**: how to put OpenSearch in
front of the expensive GET endpoints of a Spring/MySQL data service without
touching the write contract, duplicating the payload, or losing the ability to
roll back.

Java 21 · Spring Boot 3.5 · MySQL 8.4 · OpenSearch 2.19 · Hibernate Search 7.2 · Docker Compose

**Two indexing pipelines, one query interface.** A hand-rolled transactional outbox
writing through the `opensearch-java` client, and Hibernate Search's `outbox-polling`
coordination driven by entity annotations. Both index the same aggregate, both are
compared against the MySQL baseline on identical traffic, and both return identical
results. [`docs/IMPLEMENTATION-SPEC.md`](docs/IMPLEMENTATION-SPEC.md) is the
step-by-step spec for applying this to a real service.

> OpenSearch is pinned to **2.19** because Hibernate Search **7.2** rejects OpenSearch 3.
> Version 8.4 supports it, but requires Hibernate ORM 7 and therefore Spring Boot 4, so
> the pin lifts as part of that upgrade rather than on its own. The hand-rolled
> `opensearch-java` pipeline is not constrained either way.

---

## What it demonstrates

| Integration point | Where |
|---|---|
| One `SearchPort`, two adapters, one router (shadow / fallback / per-request override) | `query/ProductSearchRouter.java` |
| Write path pays one INSERT and nothing else | `write/ProductWriteService.java` |
| Transactional outbox keyed by aggregate id, `FOR UPDATE SKIP LOCKED` | `outbox/` |
| Projection built from the same loader the API hydrates with | `read/`, `projection/` |
| Bulk indexing with `external_gte` versioning (stale writes are a counted no-op) | `indexer/ProductIndexer.java` |
| Zero-downtime rebuild: new index, backfill, alias swap | `indexer/ReindexService.java` |
| Drift detection and repair through the normal pipeline | `ops/ReconciliationService.java` |
| Side-by-side benchmark of all three engines on identical semantics | `ops/BenchmarkService.java` |
| Hibernate Search mapping: derived fields with `@IndexingDependency` | `domain/Product.java` |
| Hibernate Search query adapter and admin | `query/hibernatesearch/` |
| Every gauge in one readable catalogue | `ops/CatalogMetrics.java` |
| Fan-out control: where the cost lands, and the two ways to move it off the write path | `write/PagedFanOutService.java` |
| Background indexing failures become a metric instead of a log line | `ops/IndexingFailureHandler.java` |

---

## Run it

```bash
make up          # MySQL + OpenSearch
make run         # the service, seeding 100k products on first start
```

`make run` seeds the catalogue (~40 s) and indexes it through the outbox pipeline.
Watch it drain:

```bash
watch -n1 'curl -s localhost:8080/api/admin/outbox; echo; curl -s localhost:8080/api/admin/index'
```

Then the headline:

```bash
make bench
```

Everything in Docker instead, including the service:

```bash
docker compose --profile app up -d --build
```

OpenSearch Dashboards on :5601, if you want to poke at the index by hand:

```bash
docker compose --profile tools up -d dashboards
```

---

## Try the two engines against each other

The same request, answered by either engine, with the response reporting which one
did the work and where the milliseconds went:

```bash
# the query that hurts most in SQL: AND-of-EAV-attributes + price window + sort by price
Q='status=ACTIVE&attr=material:cotton&attr=fit:slim&priceMin=20&priceMax=80&sort=PRICE&dir=ASC&size=20'

curl -s "localhost:8080/api/v1/products?$Q&engine=MYSQL"      | jq '{engine, engineMillis, hydrationMillis, total}'
curl -s "localhost:8080/api/v1/products?$Q&engine=OPENSEARCH" | jq '{engine, engineMillis, hydrationMillis, total}'
```

Write something and watch it appear in the index:

```bash
curl -s -X POST localhost:8080/api/v1/products -H 'Content-Type: application/json' -d '{
  "sku":"SKU-DEMO-1","name":"Demo cotton Shirt","description":"a demo product",
  "brand":"DemoBrand","status":"ACTIVE","vendorId":1,"categoryId":3,"currency":"EUR",
  "variants":[{"sku":"SKU-DEMO-1-v1","color":"black","size":"M","weightGrams":400,"price":42.00,
               "stock":[{"warehouseCode":"EU-WH1","region":"EU","quantity":5}]}],
  "attributes":{"material":"cotton","fit":"slim"},"tags":["new"]}'

curl -s "localhost:8080/api/admin/outbox"          # 1 pending, briefly
curl -s "localhost:8080/api/v1/products?q=Demo&engine=OPENSEARCH" | jq '.items[].name'
```

Prove the write path does not depend on the search cluster:

```bash
docker compose pause opensearch
curl -s -X POST localhost:8080/api/v1/products -H 'Content-Type: application/json' -d '{...}'   # still 201
curl -s "localhost:8080/api/v1/products?status=ACTIVE&engine=OPENSEARCH" | jq .engine           # "mysql" — fell back
docker compose unpause opensearch                                                               # backlog drains itself
```

---

## Admin endpoints

| Endpoint | Purpose |
|---|---|
| `GET  /api/admin/benchmark?iterations=15` | both engines, all scenarios, with an id-parity check |
| `GET  /api/admin/outbox` | backlog and lag |
| `GET  /api/admin/index` | aliases, target indices, document count |
| `POST /api/admin/relay/drain` | one relay cycle, synchronously |
| `POST /api/admin/reindex?moveReadAlias=true` | rebuild into a new index and cut over |
| `POST /api/admin/replay?sinceMinutes=60` | re-enqueue a window of changes |
| `GET  /api/admin/reconcile?sample=500&repair=false` | drift check |
| `GET  /api/admin/hs/status` | Hibernate Search outbox backlog, aborted events, document count |
| `POST /api/admin/hs/massindex` | Hibernate Search backfill (purges first) |
| `POST /api/admin/hs/aborted/reprocess` | Requeue events Hibernate Search abandoned after its two retries |
| `POST /api/admin/hs/aborted/clear` | Discard them instead; leaves the index behind, follow with a backfill |
| `POST /api/admin/hs/indexing/{pause\|resume}` | Turn listener-triggered indexing off for a bulk load |
| `POST /api/admin/hs/fanout/{parent}/{id}/massindex` | Manual fan-out via a scoped mass index; suspends live indexing |
| `POST /api/admin/hs/fanout/{parent}/{id}?pageSize=500` | Manual fan-out a page at a time; restartable, competes with live indexing |
| `GET  /actuator/prometheus` | all metrics |
| `POST /api/admin/seed?products=100000` | seed on demand |

## Watching the fan-out

```bash
make observability          # Prometheus + Grafana, dashboard provisioned
make fanout-demo            # rename a category: one row, thousands of index writes
```

Grafana on [localhost:3000](http://localhost:3000/d/catalog-indexing), anonymous, no
login. The headline panel is **documents indexed per user change**: ordinary product
writes sit near 1, and a change to a shared parent lifts it into the thousands.

Four metrics make that visible, and the pairing is the point — indexing work is only
interpretable next to the changes that caused it:

| Metric | What it is |
|---|---|
| `catalog_write_mutations_total{operation}` | User-level changes. The denominator of every amplification figure. |
| `catalog_outbox_events_written_total{pipeline}` | Queue rows per change — amplification at the queue. |
| `catalog_index_writes_total{pipeline}` | Documents written per change — amplification at the index. Read from the cluster's own counters, so it sees Hibernate Search's writes too, which nothing inside the application can observe. |
| `catalog_write_fanout{trigger}` | Distribution of fan-out sizes. A summary, not a sampled gauge, so it records the event whatever the scrape interval. |

That last distinction matters: a 3,079-document fan-out drains in about a second, so the
**backlog gauge can miss it entirely between two scrapes** while the summary and the
counters always catch it.

## Measuring the load indexing adds to MySQL

```bash
make db-pressure
```

Differences `SHOW GLOBAL STATUS` around a fixed 200-write burst. Measured here: the
hand-rolled outbox adds ~2.9 statements per write, Hibernate Search ~13, and the two
pollers together cost ~1,400 statements a minute at complete idle. Numbers and method in
the spec, section 6.3.

They are unauthenticated here because it is a demo. Put them behind an internal
port before this shape ships.

---

## Configuration worth knowing

```yaml
catalog:
  search:
    engine: OPENSEARCH        # MYSQL | SHADOW | OPENSEARCH | HIBERNATE_SEARCH
    shadow-candidate: OPENSEARCH   # which engine SHADOW diffs MySQL against
    fallback-to-mysql: true   # search error => answer from MySQL
    hydration-surplus: 5      # extra ids fetched so a stale index cannot shorten a page
  indexing:
    relay-enabled: true       # turn the relay off on nodes that should not index
    outbox-enabled: true      # kill switch for the write-path hook
```

Every one of those is overridable per environment, and `?engine=` overrides the
first per request — which is what makes the rollout a routing decision rather than
a deployment.

---

## Tests

```bash
make test        # unit + Testcontainers integration tests, self-contained
make e2e         # end-to-end suite against a RUNNING service
```

`make test` runs 8 unit tests (projection semantics, version sources, window arithmetic)
and 8 integration tests against real MySQL and real OpenSearch via Testcontainers --
four per pipeline: outbox delivery, child-table writes reaching the document, deletes,
and stale-version rejection or cross-engine agreement. These start their own containers.

`make e2e` runs 21 end-to-end tests over HTTP against a service that is already running,
so they exercise the deployed configuration rather than a test context. Point them
anywhere:

```bash
make e2e
mvn verify -Pe2e -f app/pom.xml -De2e.baseUrl=https://catalog.staging.internal
mvn verify -Pe2e -f app/pom.xml -De2e.engines=MYSQL,OPENSEARCH -De2e.iterations=50
```

They cover four things, and every one of them runs against **every** read path:

| Suite | What it establishes |
|---|---|
| `WriteCorrectnessE2eTest` | A write did what it said, read back through the point-lookup endpoint that never touches an index. A failure here is a write bug, not an indexing bug. |
| `IndexPropagationE2eTest` | Every kind of write — create, a reprice two tables away, a stock change three tables away, a delete — reaches every index, and how long it took. |
| `ReadPathE2eTest` | Every engine returns the same ids in the same order as MySQL across ten query shapes; pages are never short and never overlap; deep paging is refused rather than clamped; the pipelines are reconciled. |
| latency | p50/p95/p99 per scenario per engine, end-to-end and engine-only, written to `app/target/e2e-report.md`. |

The report is the point of the run. A green tick says the migration is safe; the report
says what it bought.

---

## A finding, not a footnote

While building this, comparing the two engines surfaced a real OpenSearch defect:
**descending sort on a `scaled_float` field can silently return non-maximal
documents** (2.19.6 and 3.8.0). Money here is therefore stored as `long` minor
units and ratings as `float`. Reproduce it yourself:

```bash
python3 docs/scaled-float-sort-repro.py http://localhost:9200
```

Spec section 9.1 has the details. The transferable lesson is in the rollout plan:
run shadow mode and diff the two engines before trusting either.
