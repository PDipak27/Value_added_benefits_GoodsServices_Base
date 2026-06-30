-- Entitlement revoke audit timestamp (Phase 3, §B1 / backlog A4).
-- Set when an admin revokes the entitlement via DELETE /ott/v1/entitlements/{ref}.
ALTER TABLE ott.entitlements ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ;
