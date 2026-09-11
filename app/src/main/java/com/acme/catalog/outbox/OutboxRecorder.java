package com.acme.catalog.outbox;

import com.acme.catalog.config.IndexingProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The entire footprint the OpenSearch integration leaves on the write path: one
 * call, inside the transaction that already exists, costing one small INSERT.
 * <p>
 * No projection is built here, no HTTP call is made here, and nothing about
 * OpenSearch is imported here -- if the search cluster is down, POST/PUT keep
 * working and the backlog drains later.
 */
@Component
public class OutboxRecorder {

    private final OutboxStore store;
    private final IndexingProperties properties;

    public OutboxRecorder(OutboxStore store, IndexingProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void productChanged(long productId) {
        if (!properties.outboxEnabled()) {
            return;
        }
        store.record(OutboxStore.AGGREGATE_PRODUCT, productId);
    }
}
