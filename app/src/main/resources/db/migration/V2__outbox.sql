-- Transactional outbox, "dirty key" flavour: the row carries the identity of the
-- aggregate that changed, never the payload. Consequences, all deliberate:
--   * the write path pays one small INSERT and never builds a projection inline;
--   * the relay can COALESCE N events for the same aggregate into one read+index;
--   * replay is idempotent because the relay always reads current state;
--   * no payload duplication in MySQL (an outbox row is ~70 bytes, not ~2 KB).
CREATE TABLE outbox_event (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   BIGINT      NOT NULL,
    occurred_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claimed_at     DATETIME(3) NULL,
    claimed_by     VARCHAR(64) NULL,
    attempts       INT         NOT NULL DEFAULT 0,
    last_error     VARCHAR(512) NULL,
    PRIMARY KEY (id),
    -- Supports the claim query: WHERE aggregate_type = ? AND (claimed_at IS NULL
    -- OR claimed_at < ?) ORDER BY id ... FOR UPDATE SKIP LOCKED
    KEY idx_outbox_claim (aggregate_type, claimed_at, id)
) ENGINE = InnoDB;
