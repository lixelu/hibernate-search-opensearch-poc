-- Hibernate Search's own coordination tables, required by
-- hibernate.search.coordination.strategy = outbox-polling.
--
-- Hibernate Search would create these itself under ddl-auto=update, but this project
-- owns its schema through Flyway, so the DDL is checked in. It was generated from
-- Hibernate's own metadata (jakarta.persistence.schema-generation) rather than written
-- by hand, so it matches what the library expects for MySQL exactly.
--
-- hsearch_outbox_event is the transactional outbox: one row per changed entity,
-- written inside the application's transaction, drained by the event processor.
-- hsearch_agent is the cluster registry the processors use to divide work between
-- instances -- the equivalent of this project's SELECT ... FOR UPDATE SKIP LOCKED,
-- but coordinated through leases rather than row locks.

CREATE TABLE hsearch_agent (
    id                   BINARY(16)   NOT NULL,
    type                 ENUM ('EVENT_PROCESSING_DYNAMIC_SHARDING','EVENT_PROCESSING_STATIC_SHARDING','MASS_INDEXING') NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    expiration           DATETIME(6)  NOT NULL,
    state                ENUM ('RUNNING','SUSPENDED','WAITING') NOT NULL,
    total_shard_count    INTEGER      NULL,
    assigned_shard_index INTEGER      NULL,
    tenant_id            VARCHAR(255) NULL,
    payload              LONGBLOB     NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE hsearch_outbox_event (
    id             BINARY(16)   NOT NULL,
    entity_name    VARCHAR(256) NOT NULL,
    entity_id      VARCHAR(256) NOT NULL,
    entity_id_hash INTEGER      NOT NULL,
    payload        LONGBLOB     NOT NULL,
    retries        INTEGER      NOT NULL,
    process_after  DATETIME(6)  NOT NULL,
    status         ENUM ('ABORTED','PENDING') NOT NULL,
    tenant_id      VARCHAR(255) NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE INDEX entityIdHash ON hsearch_outbox_event (entity_id_hash);
CREATE INDEX status ON hsearch_outbox_event (status);
CREATE INDEX processAfter ON hsearch_outbox_event (process_after);
