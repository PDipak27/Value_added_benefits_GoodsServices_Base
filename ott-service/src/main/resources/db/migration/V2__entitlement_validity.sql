-- Entitlement validity window (Phase 2, §B1). Supplied by the caller (fulfilment),
-- computed from the offer term; valid_until null = perpetual.
ALTER TABLE ott.entitlements ADD COLUMN IF NOT EXISTS valid_from  TIMESTAMPTZ;
ALTER TABLE ott.entitlements ADD COLUMN IF NOT EXISTS valid_until TIMESTAMPTZ;
