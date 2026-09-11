package com.acme.catalog.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.hibernate.search.engine.common.EntityReference;
import org.hibernate.search.engine.reporting.EntityIndexingFailureContext;
import org.hibernate.search.engine.reporting.FailureContext;
import org.hibernate.search.engine.reporting.FailureHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Where Hibernate Search's background failures go.
 *
 * <p>Reference documentation, section 8.5.1: failures that occur in background threads
 * "cannot be propagated" to the caller, and by default "the failure is logged at the
 * ERROR level" and nothing else happens. With the {@code outbox-polling} coordination
 * strategy <em>all</em> indexing happens in a background thread, so that default means
 * the entire indexing pipeline can be failing while every dashboard stays green.
 *
 * <p>Two failures are the reason this class exists:
 * <ul>
 *   <li>A transient cluster failure retries twice and then the event is
 *       <b>aborted</b> (section 19.3.8) and never retried again. The
 *       {@code catalog.outbox.aborted} gauge shows the aftermath; this counter shows
 *       the cause, at the moment it happens, with the entity ids attached.</li>
 *   <li>A failure during {@code MassIndexer} does not stop the run: the built-in
 *       handler "just forwards the failures to the global background failure handler"
 *       (section 14.4.6). Without this, a backfill can finish "successfully" with rows
 *       missing.</li>
 * </ul>
 *
 * <p>Wired in through {@code hibernate.search.background_failure_handler}, which takes a
 * bean reference: Spring Boot installs its own bean container into Hibernate ORM, and
 * Hibernate Search resolves the reference through it, so this stays an ordinary
 * {@code @Component} with the meter registry injected.
 *
 * <p>The entity ids are logged rather than counted per-entity on purpose. A cluster-wide
 * outage fails every entity in the batch, and a label per product id would multiply the
 * time series by the catalogue size at exactly the moment the system is least healthy.
 */
@Component
public class IndexingFailureHandler implements FailureHandler {

    private static final Logger log = LoggerFactory.getLogger(IndexingFailureHandler.class);

    /** How many entity references to name in one log line before summarising. */
    private static final int MAX_LOGGED_ENTITIES = 20;

    private final Counter genericFailures;
    private final Counter entityFailures;
    private final Counter entitiesAffected;

    public IndexingFailureHandler(MeterRegistry meters) {
        this.genericFailures = Counter.builder("catalog.hs.failures")
                .tag("kind", "generic")
                .description("Hibernate Search background failures not tied to specific entities")
                .register(meters);
        this.entityFailures = Counter.builder("catalog.hs.failures")
                .tag("kind", "entity-indexing")
                .description("Hibernate Search background failures while indexing entities")
                .register(meters);
        this.entitiesAffected = Counter.builder("catalog.hs.failures.entities")
                .description("Entities that could not be indexed because of a background failure")
                .register(meters);
    }

    @Override
    public void handle(FailureContext context) {
        genericFailures.increment();
        log.error("Hibernate Search background failure during [{}]",
                context.failingOperation(), context.throwable());
    }

    @Override
    public void handle(EntityIndexingFailureContext context) {
        entityFailures.increment();
        var references = context.failingEntityReferences();
        entitiesAffected.increment(references.size());

        StringBuilder ids = new StringBuilder();
        int shown = 0;
        for (EntityReference reference : references) {
            if (shown == MAX_LOGGED_ENTITIES) {
                ids.append(", ... ").append(references.size() - shown).append(" more");
                break;
            }
            if (shown > 0) {
                ids.append(", ");
            }
            ids.append(reference.name()).append('#').append(reference.id());
            shown++;
        }
        log.error("Hibernate Search could not index {} entities during [{}]: {}",
                references.size(), context.failingOperation(), ids, context.throwable());
    }
}
