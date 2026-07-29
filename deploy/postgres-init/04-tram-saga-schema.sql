-- ============================================================
-- Eventuate Tram Sagas — orchestration tables (eventuate schema).
--
-- Source of truth: eventuate-tram-sagas-spring-flyway
--   flyway/postgresql/V1000__tram-saga-schema.sql (0.26.0.RELEASE).
-- That jar only PROVIDES the scripts (no Spring auto-config runs them), and the
-- app's own Flyway manages the per-service schemas (orders/inventory/billing) —
-- never the eventuate schema. So, like the Tram message tables in 01-*, these
-- saga tables must be created as part of the eventuate-schema bootstrap.
--
-- Without this, order-service (the orchestrator) fails on the first saga step:
--   bad SQL grammar [INSERT INTO eventuate.saga_instance ...]
--   ERROR: relation "eventuate.saga_instance" does not exist
--
-- Only order-service (orchestrator) needs these; participants don't.
-- ============================================================

CREATE SCHEMA IF NOT EXISTS eventuate;

CREATE TABLE IF NOT EXISTS eventuate.saga_instance_participants (
    saga_type   VARCHAR(255) NOT NULL,
    saga_id     VARCHAR(100) NOT NULL,
    destination VARCHAR(100) NOT NULL,
    resource    VARCHAR(100) NOT NULL,
    PRIMARY KEY (saga_type, saga_id, destination, resource)
);

CREATE TABLE IF NOT EXISTS eventuate.saga_instance (
    saga_type       VARCHAR(255)  NOT NULL,
    saga_id         VARCHAR(100)  NOT NULL,
    state_name      VARCHAR(100)  NOT NULL,
    last_request_id VARCHAR(100),
    end_state       BOOLEAN,
    compensating    BOOLEAN,
    failed          BOOLEAN,
    saga_data_type  VARCHAR(1000) NOT NULL,
    saga_data_json  VARCHAR(1000) NOT NULL,
    PRIMARY KEY (saga_type, saga_id)
);

CREATE TABLE IF NOT EXISTS eventuate.saga_lock_table (
    target    VARCHAR(100) PRIMARY KEY,
    saga_type VARCHAR(255) NOT NULL,
    saga_id   VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS eventuate.saga_stash_table (
    message_id      VARCHAR(100) PRIMARY KEY,
    target          VARCHAR(100)  NOT NULL,
    saga_type       VARCHAR(255)  NOT NULL,
    saga_id         VARCHAR(100)  NOT NULL,
    message_headers VARCHAR(1000) NOT NULL,
    message_payload VARCHAR(1000) NOT NULL
);
