-- ============================================================
-- Eventuate Local event store schema
-- Run once against your local Postgres 18 instance.
-- psql -U postgres -d vab -f 01-eventuate-schema.sql
-- ============================================================

-- Eventuate ES tables (entity events, snapshots, monitoring)
CREATE SCHEMA IF NOT EXISTS eventuate;

CREATE TABLE IF NOT EXISTS eventuate.events (
    event_id          VARCHAR(1000) PRIMARY KEY,
    event_type        VARCHAR(1000),
    event_data        VARCHAR(1000) NOT NULL,
    entity_type       VARCHAR(1000) NOT NULL,
    entity_id         VARCHAR(1000) NOT NULL,
    triggering_event  VARCHAR(1000),
    metadata          VARCHAR(1000),
    published         SMALLINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS events_idx
    ON eventuate.events (entity_type, entity_id, event_id);

CREATE INDEX IF NOT EXISTS events_published_idx
    ON eventuate.events (published, event_id);

CREATE TABLE IF NOT EXISTS eventuate.entities (
    entity_type     VARCHAR(1000),
    entity_id       VARCHAR(1000),
    entity_version  VARCHAR(1000) NOT NULL,
    PRIMARY KEY (entity_type, entity_id)
);

CREATE TABLE IF NOT EXISTS eventuate.snapshots (
    entity_type       VARCHAR(1000),
    entity_id         VARCHAR(1000),
    entity_version    VARCHAR(1000),
    snapshot_type     VARCHAR(1000) NOT NULL,
    snapshot_json     VARCHAR(1000) NOT NULL,
    triggering_events VARCHAR(1000),
    PRIMARY KEY (entity_type, entity_id, entity_version)
);

CREATE TABLE IF NOT EXISTS eventuate.cdc_monitoring (
    reader_id  VARCHAR(1000) PRIMARY KEY,
    last_time  BIGINT
);

-- Eventuate Tram messaging tables
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

CREATE TABLE IF NOT EXISTS eventuate.received_messages (
    consumer_id    VARCHAR(1000),
    message_id     VARCHAR(1000),
    creation_time  BIGINT,
    published      SMALLINT DEFAULT 0,
    PRIMARY KEY (consumer_id, message_id)
);

CREATE TABLE IF NOT EXISTS eventuate.offset_store (
    client_name       VARCHAR(255) NOT NULL PRIMARY KEY,
    serialized_offset VARCHAR(255)
);
