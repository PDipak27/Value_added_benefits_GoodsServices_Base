-- Admin entitlement revoke (Phase 3, §B1 / backlog A4). The order stays COMPLETED;
-- this stamps when its entitlement was revoked. The entitlements_v1 read model is
-- flipped to REVOKED by the projector (frees the uniqueness slot).
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS entitlement_revoked_at TIMESTAMPTZ;
