-- Design/06 §8: OTT accepts a provisioning request (202 PENDING) and confirms it later by webhook.
--   * provisioning_task gets AWAITING_ACTIVATION (reply held until entitlement.activated / .rejected, the
--     deadline or a user cancel) and remembers OTT's external_ref.
--   * ott_webhook_event: OTT event ids already applied (webhooks are delivered at least once).

ALTER TABLE fulfilment.provisioning_task DROP CONSTRAINT IF EXISTS provisioning_task_status_check;
ALTER TABLE fulfilment.provisioning_task ADD CONSTRAINT provisioning_task_status_check
    CHECK (status IN ('RETRYING','IN_PROGRESS','AWAITING_ACTIVATION','REVOKE_OWED','CANCEL_TOMBSTONE'));
ALTER TABLE fulfilment.provisioning_task ADD COLUMN IF NOT EXISTS external_ref VARCHAR(64);

CREATE TABLE IF NOT EXISTS fulfilment.ott_webhook_event (
    event_id     VARCHAR(64)  PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
