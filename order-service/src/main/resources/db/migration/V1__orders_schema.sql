-- Order service application tables (schema: orders)
-- Eventuate event store tables live in schema: eventuate (created by init script)
-- Eventuate Tram tables are created by eventuate-tram-spring-flyway auto-migration

CREATE SCHEMA IF NOT EXISTS orders;

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
