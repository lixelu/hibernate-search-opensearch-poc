JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null)
MVN = JAVA_HOME=$(JAVA_HOME) mvn -B -f app/pom.xml

.PHONY: up down logs run test e2e e2e-quick db-pressure observability fanout-demo bench seed drain reindex reconcile status metrics hs-status hs-massindex clean

up:            ## start MySQL + OpenSearch and wait for health
	docker compose up -d mysql opensearch
	@curl -sS --retry 60 --retry-delay 2 --retry-connrefused --retry-all-errors \
	    --max-time 240 -o /dev/null http://localhost:9200/_cluster/health && echo "opensearch ready"

down:          ## stop everything and drop the volumes
	docker compose --profile app --profile tools down -v

logs:
	docker compose logs -f --tail=100

run:           ## run the service against the local stack
	$(MVN) spring-boot:run

test:          ## unit + Testcontainers integration tests
	$(MVN) verify

e2e:           ## end-to-end suite against a running service; writes app/target/e2e-report.md
	$(MVN) verify -Pe2e

e2e-quick:     ## the same suite, fewer samples
	$(MVN) verify -Pe2e -De2e.iterations=5 -De2e.warmup=2 -De2e.propagationSamples=1

observability: ## Prometheus + Grafana with the indexing dashboard (Grafana on :3000)
	docker compose --profile observability up -d
	@echo "Grafana:    http://localhost:3000/d/catalog-indexing"
	@echo "Prometheus: http://localhost:9090"

fanout-demo:   ## rename a category and watch one row become thousands of index writes
	@curl -s -X PUT "http://localhost:8080/api/admin/category/3/path?path=/apparel/shirts/demo-$$(date +%s)" && echo
	@echo "watch the amplification panel at http://localhost:3000/d/catalog-indexing"

db-pressure:   ## measure the MySQL load indexing adds (see docs, section 6.3)
	@python3 scripts/db-pressure.py snapshot > /tmp/dbp.json
	@python3 scripts/write-burst.py 40 MAKE
	@python3 scripts/db-pressure.py diff /tmp/dbp.json "200-write burst" - 200 write

bench:         ## both engines, all scenarios
	@python3 scripts/benchmark.py 15

seed:          ## seed 100k products
	curl -s -X POST "http://localhost:8080/api/admin/seed?products=100000" && echo

drain:         ## run one relay cycle
	curl -s -X POST "http://localhost:8080/api/admin/relay/drain" && echo

reindex:       ## rebuild into a new index and swap the read alias
	curl -s -X POST "http://localhost:8080/api/admin/reindex?moveReadAlias=true" && echo

reconcile:     ## drift check
	curl -s "http://localhost:8080/api/admin/reconcile?sample=500" && echo

hs-status:     ## Hibernate Search outbox + index state
	@curl -s http://localhost:8080/api/admin/hs/status && echo

hs-massindex:  ## Hibernate Search backfill
	curl -s -X POST "http://localhost:8080/api/admin/hs/massindex" && echo

metrics:       ## the catalog metrics, from the prometheus endpoint
	@curl -s http://localhost:8080/actuator/prometheus | grep -E '^catalog_' | grep -vE '_bucket|_created'

status:        ## outbox + index state
	@curl -s http://localhost:8080/api/admin/outbox && echo && curl -s http://localhost:8080/api/admin/index && echo

clean:
	$(MVN) clean
