-- V3: move inventory from the LICENSE/PHYSICAL/SLOT taxonomy to the three
-- product types (Design/09). SLOT is removed entirely. The two FINITE types are:
--   PHYSICAL_GOOD    — a stock count (reserve decrements available)
--   SOFTWARE_LICENSE — a pool of individual activation keys (reserve allocates one)
-- DIGITAL_SUBSCRIPTION is infinite and has NO row here — the saga skips reserve.
--
-- Rebuild the tables (dev only, no data to preserve): drop FK-dependent children
-- first, then recreate with the new CHECK domain and a license_keys pool.

DROP TABLE IF EXISTS inventory.reservations;
DROP TABLE IF EXISTS inventory.inventory_items;

CREATE TABLE inventory.inventory_items (
    offer_code VARCHAR(100) PRIMARY KEY,
    type       VARCHAR(20)  NOT NULL CHECK (type IN ('PHYSICAL_GOOD','SOFTWARE_LICENSE')),
    total      INTEGER      NOT NULL CHECK (total >= 0),
    reserved   INTEGER      NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    version    BIGINT       NOT NULL DEFAULT 0,
    CHECK (reserved <= total)
);

-- Individual SOFTWARE_LICENSE keys. Reserving allocates one (FREE → ALLOCATED,
-- stamped with the reservationId); release returns it (→ FREE).
CREATE TABLE inventory.license_keys (
    license_key    VARCHAR(100) PRIMARY KEY,
    offer_code     VARCHAR(100) NOT NULL REFERENCES inventory.inventory_items(offer_code),
    status         VARCHAR(16)  NOT NULL DEFAULT 'FREE' CHECK (status IN ('FREE','ALLOCATED')),
    reservation_id VARCHAR(64)
);
CREATE INDEX idx_license_keys_offer_status ON inventory.license_keys (offer_code, status);

CREATE TABLE inventory.reservations (
    reservation_id VARCHAR(64)  PRIMARY KEY,
    offer_code     VARCHAR(100) NOT NULL REFERENCES inventory.inventory_items(offer_code),
    quantity       INTEGER      NOT NULL CHECK (quantity > 0),
    license_key    VARCHAR(100),               -- SOFTWARE_LICENSE only; NULL otherwise
    released       BOOLEAN      NOT NULL DEFAULT FALSE
);

-- Seed: matches the finite catalog offers (CatalogSeeder). DIGITAL_SUBSCRIPTION
-- offers (OTT_*) are infinite and intentionally absent. SW_ANTIVIRUS_1Y has a
-- deliberately small key pool to exercise the POOL_EXHAUSTED path.
INSERT INTO inventory.inventory_items (offer_code, type, total, reserved) VALUES
    ('ACC_BUDS_PRO',      'PHYSICAL_GOOD',    30, 0),
    ('ACC_POWERBANK_20K', 'PHYSICAL_GOOD',     5, 0),
    ('SW_MSOFFICE_1Y',    'SOFTWARE_LICENSE',  5, 0),
    ('SW_ANTIVIRUS_1Y',   'SOFTWARE_LICENSE',  3, 0);

-- Activation keys: count per offer MUST equal its inventory_items.total.
INSERT INTO inventory.license_keys (license_key, offer_code) VALUES
    ('MSOFF-1Y-AAAA-0001', 'SW_MSOFFICE_1Y'),
    ('MSOFF-1Y-AAAA-0002', 'SW_MSOFFICE_1Y'),
    ('MSOFF-1Y-AAAA-0003', 'SW_MSOFFICE_1Y'),
    ('MSOFF-1Y-AAAA-0004', 'SW_MSOFFICE_1Y'),
    ('MSOFF-1Y-AAAA-0005', 'SW_MSOFFICE_1Y'),
    ('NORTON-1Y-BBBB-0001', 'SW_ANTIVIRUS_1Y'),
    ('NORTON-1Y-BBBB-0002', 'SW_ANTIVIRUS_1Y'),
    ('NORTON-1Y-BBBB-0003', 'SW_ANTIVIRUS_1Y');

-- The old LICENSE-only table (V1) is superseded by inventory_items.
DROP TABLE IF EXISTS inventory.license_pools;
