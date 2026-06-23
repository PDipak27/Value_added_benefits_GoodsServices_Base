-- OTT service application tables (schema: ott) — DD-27

CREATE SCHEMA IF NOT EXISTS ott;

CREATE TABLE IF NOT EXISTS ott.entitlements (
    external_ref    VARCHAR(64)  PRIMARY KEY,
    order_id        VARCHAR(64)  NOT NULL UNIQUE,   -- idempotency key
    subscriber_id   VARCHAR(64)  NOT NULL,
    offer_code      VARCHAR(100) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    provisioned_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
