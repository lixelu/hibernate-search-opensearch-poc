-- Write model. Deliberately normalised across 7 tables so that a "complex GET"
-- needs 4-6 joins plus correlated sub-selects -- the shape that no amount of
-- index tuning rescues once the filter/sort combination is caller-controlled.

CREATE TABLE vendor (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(160) NOT NULL,
    country     CHAR(2)      NOT NULL,
    tier        VARCHAR(16)  NOT NULL,
    rating      DECIMAL(3,2) NOT NULL DEFAULT 0.00,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_vendor_country (country),
    KEY idx_vendor_tier (tier)
) ENGINE = InnoDB;

CREATE TABLE category (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    parent_id  BIGINT       NULL,
    name       VARCHAR(120) NOT NULL,
    path       VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_category_path (path),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category (id)
) ENGINE = InnoDB;

CREATE TABLE product (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    sku               VARCHAR(64)  NOT NULL,
    name              VARCHAR(255) NOT NULL,
    description       TEXT         NULL,
    brand             VARCHAR(120) NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    vendor_id         BIGINT       NOT NULL,
    category_id       BIGINT       NOT NULL,
    currency          CHAR(3)      NOT NULL DEFAULT 'EUR',
    rating_avg        DECIMAL(3,2) NOT NULL DEFAULT 0.00,
    rating_count      INT          NOT NULL DEFAULT 0,
    launch_date       DATE         NULL,
    -- Monotonic per-aggregate counter. Bumped by every write that changes ANY
    -- table belonging to the product aggregate (variants, inventory, tags,
    -- attributes included). Used as the OpenSearch external document version so
    -- that out-of-order or replayed indexing can never install an older doc.
    aggregate_version BIGINT       NOT NULL DEFAULT 1,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_sku (sku),
    KEY idx_product_vendor (vendor_id),
    KEY idx_product_category (category_id),
    KEY idx_product_status_brand (status, brand),
    KEY idx_product_rating (rating_avg),
    KEY idx_product_updated (updated_at),
    FULLTEXT KEY ftx_product_text (name, description),
    CONSTRAINT fk_product_vendor FOREIGN KEY (vendor_id) REFERENCES vendor (id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id)
) ENGINE = InnoDB;

CREATE TABLE product_variant (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    product_id    BIGINT       NOT NULL,
    sku           VARCHAR(64)  NOT NULL,
    color         VARCHAR(32)  NOT NULL,
    size          VARCHAR(16)  NOT NULL,
    weight_grams  INT          NOT NULL DEFAULT 0,
    price         DECIMAL(12,2) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (id),
    UNIQUE KEY uk_variant_sku (sku),
    KEY idx_variant_product (product_id),
    KEY idx_variant_price (price),
    CONSTRAINT fk_variant_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE inventory (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    variant_id     BIGINT      NOT NULL,
    warehouse_code VARCHAR(16) NOT NULL,
    region         VARCHAR(8)  NOT NULL,
    quantity       INT         NOT NULL DEFAULT 0,
    reserved       INT         NOT NULL DEFAULT 0,
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_variant_wh (variant_id, warehouse_code),
    KEY idx_inventory_region_qty (region, quantity),
    CONSTRAINT fk_inventory_variant FOREIGN KEY (variant_id) REFERENCES product_variant (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Entity-attribute-value: the classic reason a GET turns into a self-join fan-out.
CREATE TABLE product_attribute (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    product_id BIGINT      NOT NULL,
    attr_key   VARCHAR(48) NOT NULL,
    attr_value VARCHAR(96) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attribute_product_key (product_id, attr_key),
    KEY idx_attribute_kv (attr_key, attr_value),
    CONSTRAINT fk_attribute_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE product_tag (
    product_id BIGINT      NOT NULL,
    tag        VARCHAR(48) NOT NULL,
    PRIMARY KEY (product_id, tag),
    KEY idx_tag (tag),
    CONSTRAINT fk_tag_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE
) ENGINE = InnoDB;
