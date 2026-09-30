-- ============================================================
-- Eventuate Tram schema — transactional outbox + consumer dedup.
-- Run once against the Postgres `vab` database (compose/k8s/Testcontainers mount it
-- into docker-entrypoint-initdb.d; locally: psql -U postgres -d vab -f 01-eventuate-schema.sql).
-- Saga orchestration tables live in 04-tram-saga-schema.sql.
-- ============================================================

CREATE SCHEMA IF NOT EXISTS eventuate;

-- Outbox: services INSERT here in the same transaction as their state change;
-- eventuate-cdc polls published=0 rows and relays them to Kafka.
CREATE TABLE IF NOT EXISTS eventuate.message (
    id                VARCHAR(1000) PRIMARY KEY,
    destination       TEXT         NOT NULL,
    headers           TEXT         NOT NULL,
    payload           TEXT         NOT NULL,
    published         SMALLINT     DEFAULT 0,
    message_partition SMALLINT,
    creation_time     BIGINT
);

CREATE INDEX IF NOT EXISTS message_published_idx
    ON eventuate.message (published, id);

-- Idempotent consumers: one row per (consumer, message) already handled.
CREATE TABLE IF NOT EXISTS eventuate.received_messages (
    consumer_id    VARCHAR(1000),
    message_id     VARCHAR(1000),
    creation_time  BIGINT,
    published      SMALLINT DEFAULT 0,
    PRIMARY KEY (consumer_id, message_id)
);

-- CDC bookkeeping: reader heartbeat/lag monitoring and stored reader offsets.
CREATE TABLE IF NOT EXISTS eventuate.cdc_monitoring (
    reader_id  VARCHAR(1000) PRIMARY KEY,
    last_time  BIGINT
);

CREATE TABLE IF NOT EXISTS eventuate.offset_store (
    client_name       VARCHAR(255) NOT NULL PRIMARY KEY,
    serialized_offset VARCHAR(255)
);
