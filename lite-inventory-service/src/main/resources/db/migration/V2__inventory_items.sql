-- V2: generalise inventory from LICENSE-only pools to all three types
-- (LICENSE / PHYSICAL / SLOT), and record reservations so release/compensation
-- can actually return stock (the V1 release was a no-op).

CREATE TABLE IF NOT EXISTS inventory.inventory_items (
    offer_code     VARCHAR(100) PRIMARY KEY,
    type           VARCHAR(20)  NOT NULL CHECK (type IN ('LICENSE','PHYSICAL','SLOT')),
    service_center VARCHAR(100),                       -- SLOT only; NULL otherwise
    total          INTEGER      NOT NULL CHECK (total >= 0),
    reserved       INTEGER      NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    version        BIGINT       NOT NULL DEFAULT 0,
    CHECK (reserved <= total)
);

CREATE TABLE IF NOT EXISTS inventory.reservations (
    reservation_id VARCHAR(64)  PRIMARY KEY,
    offer_code     VARCHAR(100) NOT NULL REFERENCES inventory.inventory_items(offer_code),
    quantity       INTEGER      NOT NULL CHECK (quantity > 0),
    released       BOOLEAN      NOT NULL DEFAULT FALSE
);

-- Seed: matches the 7 active catalog offers (DIGITAL→LICENSE, PHYSICAL→PHYSICAL,
-- SLOT→SLOT). ACC_POWERBANK_20K is intentionally low-stock for an exhaustion test.
INSERT INTO inventory.inventory_items (offer_code, type, service_center, total, reserved) VALUES
    ('OTT_NETFLIX_6M',       'LICENSE',  NULL,        100, 0),
    ('OTT_PRIME_12M',        'LICENSE',  NULL,         50, 0),
    ('OTT_HOTSTAR_3M',       'LICENSE',  NULL,         75, 0),
    ('ACC_BUDS_PRO',         'PHYSICAL', NULL,         30, 0),
    ('ACC_POWERBANK_20K',    'PHYSICAL', NULL,          5, 0),
    ('REPAIR_PRIORITY_SLOT', 'SLOT',     'SC_MUMBAI',  20, 0),
    ('REPAIR_EXPRESS_SLOT',  'SLOT',     'SC_DELHI',   10, 0)
ON CONFLICT (offer_code) DO NOTHING;

-- The old LICENSE-only table is superseded by inventory_items.
DROP TABLE IF EXISTS inventory.license_pools;
