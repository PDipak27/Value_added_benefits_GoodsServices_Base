-- Order service application tables (schema: orders)
-- Post-DD-14: the Order aggregate is state-stored in orders.orders (not event-sourced).
-- Eventuate Tram outbox (eventuate.message) lives in schema: eventuate (created by init script).
-- Eventuate Tram saga/dedup tables are created by eventuate-tram-spring-flyway auto-migration.

CREATE SCHEMA IF NOT EXISTS orders;

-- State-stored Order aggregate (one updatable row per order; version = optimistic lock)
CREATE TABLE IF NOT EXISTS orders.orders (
    order_id          VARCHAR(255) PRIMARY KEY,
    version           BIGINT       NOT NULL,
    subscriber_id     VARCHAR(255) NOT NULL,
    offer_code        VARCHAR(255) NOT NULL,
    price_snapshot_id VARCHAR(255),
    amount            BIGINT       NOT NULL,
    currency          VARCHAR(8)   NOT NULL,
    billing_mode      VARCHAR(32),
    status            VARCHAR(32)  NOT NULL,
    placed_at         TIMESTAMPTZ,
    confirmed_at      TIMESTAMPTZ,
    failed_step       VARCHAR(64),
    failure_reason    TEXT
);

CREATE INDEX IF NOT EXISTS idx_orders_subscriber
    ON orders.orders (subscriber_id);

CREATE TABLE IF NOT EXISTS orders.idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,
    subscriber_id   VARCHAR(255)  NOT NULL,
    idempotency_key VARCHAR(36)   NOT NULL,
    order_id        VARCHAR(255)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_idempotency UNIQUE (subscriber_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_lookup
    ON orders.idempotency_keys (subscriber_id, idempotency_key);
