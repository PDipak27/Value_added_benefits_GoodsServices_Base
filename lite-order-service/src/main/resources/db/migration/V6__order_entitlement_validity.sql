-- Entitlement validity window persisted on the order at complete (Phase 2, §B1).
-- Set for benefit types (DIGITAL_SUBSCRIPTION / SOFTWARE_LICENSE); null otherwise.
-- valid_until null = perpetual. term_months is snapshotted at placement so an
-- admin re-drive (DD-27) can recompute the validity window.
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS term_months INTEGER;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS valid_from  TIMESTAMPTZ;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS valid_until TIMESTAMPTZ;
