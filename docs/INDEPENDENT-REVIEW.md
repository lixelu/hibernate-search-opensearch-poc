# Independent review — was OpenSearch the right call?

**What this is.** A second opinion commissioned after the reference implementation was
already working, deliberately framed as "reevaluate this, don't defend it". The reviewer
read the repository, then built the strongest alternative — a denormalised MySQL read
table — against the same dataset and benchmarked it head to head at 1x and 10x. The
numbers below are theirs, measured on the running stack, not carried over from the other
documents in this directory.

**Status of its findings.** Two of its corrections were verified independently and are
already applied to this repository. Where the report describes something as a defect, check
the note before assuming it is still true:

| Finding | State |
|---|---|
| The OpenSearch 3.x compatibility claim in the spec was wrong | **Fixed.** Confirmed by decompiling `ElasticsearchDialectFactory` in both 7.2.6 and 8.4.0. Spec and README corrected. |
| `exactTotalHits` defaulted to `false` | **Fixed.** Now defaults to `true`, and the Hibernate Search adapter asks the result whether its count is exact instead of hardcoding it. |
| Delete one of the two indexing pipelines | **Open.** Both are still present, because comparing them is the point of this project. The recommendation stands for a real service. |
| Index `category.id` rather than `category.path` | **Open.** Named as option 3 in the spec, not taken here. |
| Range-checksum reconciliation | **Open.** Still the sampled version. |

**Reproducibility.** During measurement the reviewer created a `product_read` table in the
`catalog` database (74.8 MB with indexes) and left it in place as evidence. A 1M-row
`product_read_10x` table and a `products-10x` OpenSearch index were created and dropped.
Nothing else was modified.

---

## Summary

**Yes — keep it. But you have built roughly twice the machinery you need, and one of the
constraints your spec treats as load-bearing is no longer true.**

I rebuilt the strongest alternative (a denormalised MySQL read table) against your actual
dataset and benchmarked it head-to-head with your live index, at 1x and at 10x. The result
decides the question, and not in the way the raw 275x headline suggests.

---

## Part 1 — Was OpenSearch the right call?

### What I measured

All numbers below are mine, taken on the running stack: MySQL 8.4.11 (1 GB buffer pool),
OpenSearch 2.19.6, warm, same machine, exact counts on, 5 runs after warm-up. I reproduced
the baseline first to confirm the machine agrees with the spec: the EAV worst case costs
**~1,200 ms for the page plus ~1,200 ms for the `COUNT(*)`** — the same cost class as the
1652 ms in the spec.

Then I built `product_read`: one wide row per product, price/stock aggregates pre-reduced,
tags/attributes/regions as JSON arrays with MySQL multi-valued indexes, plus composite
indexes for each sort. It populates in **3.2 s** for all 101,363 rows.

**At 101,363 products (exact counts, page of 20):**

| Scenario | MySQL joins (today) | MySQL read table | OpenSearch |
|---|---|---|---|
| EAV + price window + sort by price | ~2,400 ms | **57–65 ms** | **5 ms** |
| status page by date (81,576 hits) | — | 19 ms | 3 ms |
| substring `name LIKE '%cotton%'` (17,379 hits) | — | 94 ms | 5 ms |
| everything at once (0 hits) | — | 0.5 ms | 2 ms |
| storage | 207 MB base tables | **+74.8 MB inside the primary** | 22.4 MB outside it |

**At 1,013,630 products — the same data, 10x:**

| Scenario | MySQL read table | OpenSearch |
|---|---|---|
| EAV + price window, page only | 1.4 ms | 13 ms *(incl. exact count)* |
| EAV **exact count** | **660 ms** | *(included above)* |
| status page by date | 120 ms | 8 ms *(incl. exact count)* |
| substring **exact count** | **1,700 ms** | 12 ms *(incl. exact count)* |
| storage | **733 MB** | 176 MB |

Totals were identical across engines at both scales (4,699 / 46,990; 81,576 / 815,760;
17,379 / 173,790), so the comparison is semantically sound, not a shape mismatch.

### What this settles

**The denormalised MySQL read table buys you exactly one order of magnitude, and gives it
back at 1M rows.** It is genuinely good at 100k — a 40x improvement on your worst query
with no new datastore — and the spec undersells it. But it fails on precisely two of the
three capabilities named:

- **Exact counts.** The page query stays fast because `LIMIT 20` lets MySQL stop early. The
  `COUNT(*)` cannot stop early: it is a scan of the matching set, linear in rows. 56 ms →
  660 ms from 1x to 10x.
- **Substring.** `LIKE '%cotton%'` is a scan by definition. 94 ms → 1,700 ms.

And a finding worth knowing before anyone proposes this: **MySQL never used the
multi-valued indexes.** Not with `MEMBER OF`, not with `JSON_CONTAINS`, not with
`FORCE INDEX`, not after `ANALYZE TABLE`. The reason is not a bug — the index cardinality
is 18, because there genuinely are only ~18 distinct `key=value` attribute pairs. Each
value matches ~5,600 of 101,363 rows, so MySQL correctly judges a 15 ms table scan cheaper
than an index lookup that would return 21,284 rows and then fetch them. **Multi-valued
indexes only pay at high attribute cardinality.** OpenSearch has no equivalent problem
because it intersects postings lists and never materialises rows.

So the combinatorics argument in the spec is right, but for a sharper reason than it
states: it is not that B-trees can't span the filters, it is that **low-selectivity
predicates plus a mandatory exact count force MySQL into a linear scan no index can
remove.**

### Where ids-plus-hydrate genuinely earns its keep

D1 is the best decision in the document, and I would not change it:

- **Fallback returns correct data.** Because both adapters return ordered ids, an
  OpenSearch outage degrades to slow, not wrong. This is what makes the whole thing
  reversible in one config change, and it is worth more than the latency.
- **Field values are never stale** — only membership and ordering. That halves the blast
  radius of every indexing bug you will ever have.
- **Adding an API field needs no reindex.** Directly serves the maintenance criterion.
- **No PII in the cluster, by construction** — a data-protection property you get for free
  and would have to fight for later.
- 22.4 MB vs 176 MB at 10x, outside the primary's buffer pool rather than competing with
  the write path for it.

The 5 ms hydration tax is real and it is cheap. The one place it stops being cheap is the
`size=1000` contract, where the project's own measurement shows the advantage collapsing to
~2x. If a hot list endpoint runs at size=1000, put that list's display fields in the
document and skip the hop **for that endpoint only**. D1 is a default, not a doctrine — the
spec already says this and it is right.

### Where it is overkill

**Two complete indexing pipelines were built and one must be deleted.** Running both costs
1,421 statements/minute at complete idle and 32.8 statements per write against 19.8 for
one. That is pure waste against the data-cost criterion.

### The ongoing cost, stated plainly

Three failure modes, in order of how much they should worry you:

1. **Fan-out is unbounded by design.** Renaming one category resolves inside the writer's
   transaction and writes 3,079 outbox rows. At 10x a top-level category is millions of rows
   written synchronously in a web request. The `max-parent-fan-out` guard turns that into a
   4xx — meaning a legitimate admin operation becomes impossible through the API. A
   `PagedFanOutService`, a scoped mass indexer, and a Grafana panel now exist to manage a
   problem that is **optional** (see Part 3).
2. **Hibernate Search abandons work silently.** A 30-second outage marked 1,740 events
   ABORTED, permanently, and restoring the cluster changed nothing. The default trace is a
   log line. This is now closed with a `FailureHandler`, a gauge and a reprocess endpoint —
   but note what that means: the library's "less code to own" advantage is largely spent
   buying back observability it does not ship. It has no metrics at all, by design, and that
   is not changing.
3. **Reconciliation is the only real safety net,** and the sampled version present
   (`ORDER BY RAND()`, 500 rows) finds nothing at scale and cannot detect orphans. The
   spec's range-checksum design is the right one; it is not built yet.

### What would have to be true for "you didn't need this"

All of these, together:

- Volume stays under ~1M products **and** you are confident it will.
- Exact counts are not contractual on the low-selectivity endpoints — or those endpoints cap
  the matching set.
- Substring search is not required on high-cardinality fields (prefix or word matching
  suffices).
- No free-text relevance ranking beyond what `MATCH … AGAINST` gives.
- You accept a synchronous write path, so the read table can be maintained in the same
  transaction and the entire async pipeline disappears.

That last one matters more than it looks. **The expensive, risky part of what was built is
the ingestion pipeline, not OpenSearch.** Outbox, versioning, fan-out, reconciliation,
abandoned events, staleness windows — none of it is OpenSearch's fault, and almost every
alternative that actually solves the problem needs the same machinery. The only way to avoid
it is to give up the third criterion and maintain the read model synchronously.

And I measured what that would cost: refreshing one product's read row is **0.1–0.5 ms**,
and the category-3 fan-out done synchronously as one `UPDATE` is **27–31 ms** — against
11 ms for the hand-rolled outbox `INSERT … SELECT`, which then still owes you a 1.6–4 s
async drain. **Synchronous is roughly 3x the write-transaction cost and zero of the
pipeline.** At current fan-out sizes that trade is much closer than the spec implies. It
stops being close at 10x, and it holds row locks on 3,079 rows for 30 ms, which is a
contention story, not just a latency one.

---

## Part 2 — The alternatives

### Two corrections to the spec first

**1. Hibernate Search is no longer pinned to OpenSearch 2.x.** Hibernate Search **8.4
supports OpenSearch 1.3 → 3.6** ([compatibility matrix](https://hibernate.org/search/releases/8.4/)),
and `opensearch:3.5` is a valid configured version. The inference from the dialect class
names was wrong — `OpenSearch214ModelDialect` is the lower bound of a range, not a ceiling.
The real constraint is the dependency chain: 8.4 requires **Hibernate ORM 7.4**, i.e.
**Spring Boot 4**.

**2. The current pairing is outside the supported matrix.** Hibernate Search **7.2 supports
OpenSearch 1.3 → 2.16** ([7.2 series](https://hibernate.org/search/releases/7.2/)); this
project runs 2.19.6. It works, but it is untested territory, and 7.2 is in *limited
support* — bugs fixed only "if considered significant enough."

**3. Spring Boot 3.5 reached OSS end of life on 30 June 2026**, final OSS release 3.5.16 —
the exact version the spec verified against. The project is already on an unsupported branch
for security patches. This reframes the whole maintenance question: **you are moving to
Boot 4 regardless**, and when you do, the OpenSearch 3.x objection evaporates.

### Staying in MySQL

| Option | Verdict |
|---|---|
| **Denormalised read table** | Measured above. The only MySQL option that actually works, and it works for one order of magnitude. Needs the same outbox already built, or a synchronous write path. Costs 74.8 MB now / 733 MB at 10x **inside the primary**. |
| **Generated/stored columns + functional indexes** | **Cannot solve the problem.** A generated column's expression may only reference columns in the same row — no subqueries, no other tables. "Cheapest variant price" is `MIN()` over a child table, so it can never be a generated column. It must be a real column maintained by the write path, which is the read table above. Useful only for same-row derivations. |
| **Covering indexes** | Reduce the constant factor. Already done. They cannot span tables and cannot serve an exact count over an unindexable predicate. |
| **InnoDB FULLTEXT with ngram** | Works for substring, at a price worth knowing. `ngram_token_size` is a **server-global, read-only variable requiring a restart**, and changing it forces every ngram index on the server to be rebuilt. Worse: MySQL will not use **more than one FULLTEXT index in a query**, and the optimizer team has declared this [won't-fix](https://dev.mysql.com/doc/refman/8.4/en/fulltext-restrictions.html). So an ngram index on `(name, description)` **replaces** the word-based one rather than complementing it — you cannot have good word search and good substring search on the same columns simultaneously. |
| **Read replicas** | Same query plan, same cost. Moves contention, solves nothing. Genuinely useful for one thing the spec already names: pointing the relay's aggregate loads off the primary. |

**Descending indexes are real in MySQL 8.0+**, so mixed-direction multi-column sorts
(`ORDER BY a ASC, b DESC`) can avoid a filesort — but only when a composite index matches
both the equality prefix and the full sort order. With ~12 optional filters and 5 sort
fields that is hundreds of indexes you cannot have. Sorting is the capability MySQL handles
*least* badly of the three; counting is the one it cannot fix.

### Postgres

**Not worth naming as a migration.** This is MySQL 8.4 (LTS, supported to ~2032) with a
heavily denormalised schema and a Spring/JPA write path. A Postgres migration is a
multi-quarter project whose payoff is `pg_trgm` GIN indexes for substring and
`pg_search`/`pg_textsearch` for BM25 — none of which beats what is already running. If this
were greenfield the calculus would differ; it is not. The honest framing is that "move to
Postgres for search" costs more than "run a search engine," and the search engine is already
running.

For completeness: `pg_trgm` with GIN is the real answer for indexed `LIKE '%x%'`, and
[ParadeDB `pg_search`](https://github.com/paradedb/paradedb) (Tantivy embedded in Postgres)
and [`pg_textsearch`](https://github.com/timescale/pg_textsearch) (Tiger Data, v1.0 April
2026) are both production-grade BM25 options. Note `pg_search` was withdrawn from new Neon
projects in March 2026 — check your managed provider supports the extension before betting
on it.

### Other search engines

Nothing here beats staying on OpenSearch, and two are disqualified outright:

- **Meilisearch — eliminated.** No infix search, by design. Exact counts capped at
  `maxTotalHits`, **default 1000**. And the licence is now `MIT AND BUSL-1.1`: replication
  and sharding are enterprise-only and require a paid licence for production.
- **Quickwit — eliminated by its own documentation**, which lists "low-latency search for
  e-commerce websites" and mutable data under *when not to use*. Acquired by Datadog,
  Jan 2025.
- **Typesense** — the best structural match in the field; its joins documentation is
  literally this schema. But exact counts hold only for filter-only queries
  (`max_candidates` defaults to 4 for text), sorting is **capped at 3 fields**, infix uses
  **only the first word of the query**, and it is RAM-resident with full-copy replication
  (3-node HA = 3x RAM). Bus-factor is concentrated.
- **Manticore** — genuinely strong: exact counts by default, full SQL `ORDER BY`, speaks the
  MySQL wire protocol, and publishes the most honest substring cost number anywhere
  (**6.4 MB → 94.3 MB, 14.7x, for `min_infix_len=3`**). A credible OpenSearch alternative.
  Not credible enough to justify replacing a working system.
- **Elasticsearch** — same Lucene core, same DSL; not operationally different for this
  workload. Triple-licensed AGPLv3/SSPL/ELv2 since 2024, but the distributions you actually
  download remain ELv2. No reason to switch.
- **Solr 10.0** (March 2026, Lucene 10.3, Java 21 minimum) is alive and actively developed —
  8 distinct committers in the last 10 commits. Apache-2.0, no licence risk, exact counts
  always. A reasonable choice not made here; not worth changing to.

### Analytical / columnar stores

**All wrong for this shape, and the spec was right to say so.** The blunt version: they are
built for large scans and aggregations, and every one of them has a "point query" fast path
whose preconditions exclude this workload.

- **ClickHouse** documents that its sparse index "is not optimized for fast single-row point
  lookups"; Altinity measured ~4K QPS for single-ID lookups against ~125K for Redis on the
  same box. Also note `MaterializedMySQL` was **removed in 25.1** — MySQL ingest is now
  ClickPipes (cloud-only) or Debezium.
- **Doris / StarRocks** both ship a short-circuit point-query path requiring a Unique/Primary
  Key table plus **equality predicates covering the full primary key**. Doris's headline
  30,000 QPS is a YCSB single-row number. A 12-optional-filter, range-and-IN, sorted,
  paginated query never takes that path.
- **DuckDB** is disqualified structurally before the columnar argument even applies: **one
  writer, and multi-process access is read-only**. A multi-instance Spring Boot service
  cannot share a DuckDB file. The MySQL extension is a live pass-through with no CDC or
  incremental sync.
- **MySQL HeatWave** fails twice: it is **cloud-only** (no on-prem or self-managed exists),
  and its optimizer offloads only queries needing "significant query acceleration" — a
  selective query returning 20 rows stays on InnoDB. You would pay for a column store and
  watch your queries not use it.

### Embedded / in-process

**Lucene embedded in your JVM is the one genuinely interesting option not considered.**
Apache-2.0, Lucene 10.5.1, same JVM, no FFM boundary, exact `totalHits`, unrestricted
mixed-direction multi-sort, and total control of the ngram trade-off. Solr, Elasticsearch
and OpenSearch are all this pattern with a server bolted on.

What you take on is the reason people buy the server: **your index becomes node-local state
in a stateless service.** Replication, rebuild-on-deploy and cross-instance consistency
become yours. Viable if you run few instances and can rebuild from MySQL cheaply — at 101k
documents the rebuild here is 14.7 s, so that part is genuinely cheap. Painful the moment you
autoscale. I would not switch to it, but it is the honest answer to "is there something
lighter than a cluster," and the answer is yes, at the cost of owning distribution yourself.

---

## Part 3 — What I would actually do

**1. Keep OpenSearch and the ids-plus-hydrate design.** The 10x measurement settles it: the
cheapest credible alternative fails on exact counts and substring — two of the three named
capabilities — at a volume you could plausibly reach, while costing 4x the storage *inside*
the primary.

**2. Delete the Hibernate Search pipeline. Run the hand-rolled outbox.** This is the largest
single win available against the stated criteria:

- **Criterion 2:** ~2.9 statements per write against ~13 — Hibernate Search costs **4.5x
  more MySQL load** for the same documents, structurally (one outbox row per touched entity,
  ORM entity-graph loads instead of batched key lookups, plus an agent heartbeat).
- **Criterion 1:** it ships no metrics, ever — not a gap that closes, it is out of scope for
  the library. And its default behaviour is to **abandon events permanently and silently**
  after two retries.
- It drags a four-way version matrix (Search ↔ ORM ↔ Boot ↔ OpenSearch) through every future
  upgrade.

I would change my mind on one condition: **if a material share of writes bypasses
`ProductWriteService`** — native SQL, `JdbcTemplate` batch jobs, bulk imports, another
service on the schema, a DBA. Then the explicit two-line hook is the bigger hazard,
Hibernate Search doesn't save you either (it sees only what goes through Hibernate), and the
correct answer is CDC. Debezium 3.6.2.Final (Sept 2026) is healthy; use Debezium Server so
you don't inherit Kafka. The spec is right that this migration is additive — the projection
assembler and indexer are unchanged.

**3. Stop indexing `category.path`. Index `category.id`, and resolve paths to ids in the
application before querying.** This deletes the worst operational hazard outright rather than
managing it. No fan-out, no `max-parent-fan-out` guard, no `PagedFanOutService`, no scoped
mass index, no suspended-agent alerting, no Grafana panel for documents-per-user-change. Do
the same for vendor. The spec names this as option 3 and then builds the other two anyway —
it is the highest-value simplification on the table and it serves the maintenance criterion
directly. The cost is one id-resolution lookup at query time, which is cacheable and tiny.

**4. Flip `exactTotalHits` to default `true` in `SearchProperties`.** It is currently
`@DefaultValue("false")` with a 10,000 cap, rescued only by a line in `application.yml`. If
that line doesn't survive the move into the real service, counts silently become a lower
bound — and counts are required to be exact. The safe behaviour should be the default, not a
config line someone has to remember. (Also worth confirming the Hibernate Search adapter
isn't hardcoding `totalIsLowerBound = false` over a capped count, if you keep it at all.)

**5. For substring, use the `wildcard` field type — not a trigram sub-field, not a leading
wildcard on a keyword.** I verified it works in OpenSearch 2.19.6: it matched `*otto*` (a
true infix, inside a word) correctly. It indexes substrings directly instead of scanning the
term dictionary, so cost stops tracking distinct-term count — which is the thing that takes
the measured wildcard from 9 ms at 100k terms to 43 ms at 1M. OpenSearch's own documented
sizing example is a keyword field's 2.3 MB of term text against the wildcard field's 16 KB,
for an index only ~13% larger. That is a better trade than the ~35% the spec budgets for
trigrams, but it is their number on log lines — **measure it on your names and SKUs before
committing.** Note `doc_values` defaults to false on the type, so it is a filter field only;
you cannot sort or aggregate on it, which you don't need to.

**6. Build the range-checksum reconciliation before you scale.** The sampled version cannot
find drift at 10x and cannot detect orphans at all. This is the only real safety net and it
is the piece that isn't built.

**7. Plan the Spring Boot 4 / Hibernate ORM 7 move on its own merits** — the project is
unsupported for security patches as of 30 June 2026. It is not a search decision, but it is
the one that unblocks OpenSearch 3.x, which you will want before 2.x leaves maintenance.

### What would change my recommendation

- **Volume is firmly capped near 100k and will stay there.** Then the read table wins on
  every criterion — 40x on the worst query, no new datastore, no async pipeline, no
  staleness — and you should seriously consider maintaining it synchronously and deleting
  all of this. My 10x numbers are the argument against; if 10x isn't coming, the argument
  evaporates.
- **Any endpoint contractually needs transactionally-consistent counts.** No async read
  model can provide that. Those endpoints stay on MySQL permanently, and you should decide
  now which they are.
- **Writes bypass the ORM in bulk.** Go to CDC, as above.
- **The hottest list endpoint runs at `size=1000`.** Then ids-plus-hydrate is paying two
  round trips for a ~2x win. Put that endpoint's display fields in the document.

### One thing the project got right that deserves saying

The `scaled_float` descending-sort defect is a real bug, it was found by diffing engines
rather than by review, and the conclusion drawn from it — that an engine change is a
correctness change and shadow mode is not optional — is the most valuable sentence in the
whole spec. Whatever you delete, keep the shadow mode and the parity checks.

---

## Sources

[Hibernate Search releases](https://hibernate.org/search/releases/) ·
[Hibernate Search 8.4](https://hibernate.org/search/releases/8.4/) ·
[Hibernate Search 7.2](https://hibernate.org/search/releases/7.2/) ·
[Spring Boot support policy](https://spring.io/support-policy/) ·
[Spring Boot 4.0 GA](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now/) ·
[MySQL EOL notice](https://www.mysql.com/support/eol-notice.html) ·
[MySQL ngram parser](https://dev.mysql.com/doc/refman/8.4/en/fulltext-search-ngram.html) ·
[MySQL FULLTEXT restrictions](https://dev.mysql.com/doc/refman/8.4/en/fulltext-restrictions.html) ·
[MySQL multi-valued indexes WL#8955](https://dev.mysql.com/worklog/task/?id=8955) ·
[MySQL ORDER BY optimization](https://dev.mysql.com/doc/refman/8.4/en/order-by-optimization.html) ·
[OpenSearch wildcard field type](https://docs.opensearch.org/latest/mappings/supported-field-types/wildcard/) ·
[OpenSearch release schedule](https://opensearch.org/releases/) ·
[pg_trgm](https://www.postgresql.org/docs/current/pgtrgm.html) ·
[ParadeDB](https://github.com/paradedb/paradedb) ·
[pg_textsearch](https://github.com/timescale/pg_textsearch) ·
[Meilisearch enterprise licence](https://www.meilisearch.com/blog/enterprise-license) ·
[Meilisearch pagination limits](https://www.meilisearch.com/docs/guides/front_end/pagination) ·
[Typesense search API](https://typesense.org/docs/30.2/api/search.html) ·
[Manticore wildcard settings](https://manual.manticoresearch.com/Creating_a_table/NLP_and_tokenization/Wildcard_searching_settings) ·
[Quickwit: when not to use](https://quickwit.io/docs/overview/introduction) ·
[Datadog acquires Quickwit](https://www.datadoghq.com/blog/datadog-acquires-quickwit/) ·
[ClickHouse key-value benchmark](https://altinity.com/blog/clickhouse-in-the-storm-part-2) ·
[Doris point query](https://doris.apache.org/docs/3.x/query-acceleration/high-concurrent-point-query/) ·
[StarRocks hybrid storage](https://docs.starrocks.io/docs/table_design/hybrid_table/) ·
[DuckDB concurrency](https://duckdb.org/docs/current/connect/concurrency.html) ·
[HeatWave cloud platforms](https://dev.mysql.com/doc/heatwave/en/mys-hw-supported-cloud-platforms.html) ·
[HeatWave query offload](https://dev.mysql.com/doc/heatwave/en/mys-hw-running-queries.html) ·
[Solr news](https://solr.apache.org/news.html) ·
[Debezium Platform status](https://debezium.io/blog/2026/07/10/debezium-platform-status-update/)
