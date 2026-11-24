-- Async provisioning + partner webhooks (Design/06 §8).
--   * entitlements: PENDING until the activator turns them ACTIVE (or REJECTED) a moment later.
--   * webhook_events: outbox of partner notifications, written in the same transaction as the state change
--     and delivered (signed, retried) by WebhookDelivery.

ALTER TABLE ott.entitlements ADD COLUMN IF NOT EXISTS activated_at     TIMESTAMPTZ;
ALTER TABLE ott.entitlements ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(200);

CREATE TABLE IF NOT EXISTS ott.webhook_events (
    id               VARCHAR(40)   PRIMARY KEY,
    event_type       VARCHAR(40)   NOT NULL,
    payload          TEXT          NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING',   -- PENDING | DELIVERED | FAILED
    attempts         INT           NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_error       VARCHAR(300),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_webhook_events_due ON ott.webhook_events (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS ix_entitlements_pending ON ott.entitlements (provisioned_at) WHERE status = 'PENDING';
