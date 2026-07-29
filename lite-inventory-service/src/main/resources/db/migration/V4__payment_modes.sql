-- V4: payment-mode redesign (DD-23).
--   * DIGITAL_SUBSCRIPTION is now FINITE — it gets an inventory row + count.
--   * inventory_items gains an `allocated` count alongside `reserved`
--     (available = total - reserved - allocated).
--   * reservations gain a lifecycle `status` (RESERVED|ALLOCATED|RELEASED) and a
--     `reserved_until` expiry for the PAY_NOW temporary-hold sweeper; the old
--     `released` boolean is folded into `status`.

-- Allow the third type.
ALTER TABLE inventory.inventory_items DROP CONSTRAINT IF EXISTS inventory_items_type_check;
ALTER TABLE inventory.inventory_items
    ADD CONSTRAINT inventory_items_type_check
    CHECK (type IN ('PHYSICAL_GOOD','SOFTWARE_LICENSE','DIGITAL_SUBSCRIPTION'));

-- Firm-hold count + capacity invariant (reserved + allocated <= total).
ALTER TABLE inventory.inventory_items ADD COLUMN IF NOT EXISTS allocated INTEGER NOT NULL DEFAULT 0;
ALTER TABLE inventory.inventory_items DROP CONSTRAINT IF EXISTS inventory_items_check;
ALTER TABLE inventory.inventory_items
    ADD CONSTRAINT inventory_items_capacity_check CHECK (reserved + allocated <= total);
ALTER TABLE inventory.inventory_items
    ADD CONSTRAINT inventory_items_allocated_check CHECK (allocated >= 0);

-- Reservation lifecycle + temporary-hold expiry.
ALTER TABLE inventory.reservations
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'RESERVED';
ALTER TABLE inventory.reservations
    ADD COLUMN IF NOT EXISTS reserved_until TIMESTAMPTZ;
UPDATE inventory.reservations SET status = 'RELEASED' WHERE released = TRUE;
ALTER TABLE inventory.reservations DROP COLUMN IF EXISTS released;
ALTER TABLE inventory.reservations
    ADD CONSTRAINT reservations_status_check CHECK (status IN ('RESERVED','ALLOCATED','RELEASED'));
CREATE INDEX IF NOT EXISTS idx_reservations_sweep
    ON inventory.reservations (status, reserved_until);

-- Seed finite DIGITAL_SUBSCRIPTION offers (OTT_*). OTT_LEGACY_3M stays absent so
-- ordering a withdrawn offer still yields ITEM_NOT_FOUND.
INSERT INTO inventory.inventory_items (offer_code, type, total, reserved, allocated) VALUES
    ('OTT_NETFLIX_6M', 'DIGITAL_SUBSCRIPTION', 100, 0, 0),
    ('OTT_PRIME_12M',  'DIGITAL_SUBSCRIPTION', 100, 0, 0),
    ('OTT_HOTSTAR_3M', 'DIGITAL_SUBSCRIPTION', 100, 0, 0);
