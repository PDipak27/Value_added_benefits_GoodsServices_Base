-- Inventory service application tables (schema: inventory)

CREATE SCHEMA IF NOT EXISTS inventory;

CREATE TABLE IF NOT EXISTS inventory.license_pools (
    offer_code      VARCHAR(100) PRIMARY KEY,
    total_seats     INTEGER      NOT NULL CHECK (total_seats > 0),
    reserved_seats  INTEGER      NOT NULL DEFAULT 0 CHECK (reserved_seats >= 0),
    version         BIGINT       NOT NULL DEFAULT 0
);

-- Seed data: 100 seats for the OTT Netflix bundle (walking skeleton)
INSERT INTO inventory.license_pools (offer_code, total_seats, reserved_seats)
VALUES ('OTT_NETFLIX_6M', 100, 0)
ON CONFLICT (offer_code) DO NOTHING;
