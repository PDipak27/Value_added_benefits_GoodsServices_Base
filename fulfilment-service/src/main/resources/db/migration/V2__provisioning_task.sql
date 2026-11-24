-- Design/13 §6: DIGITAL_SUBSCRIPTION provisioning that is waiting for OTT. One row per order, kept only
-- while needed. The saga's reply token is stored here; the reply is sent (exactly once) when the row is
-- closed: provisioned, rejected, deadline passed, or cancelled by the user.
--
-- status:
--   RETRYING          waiting for the next attempt (next_attempt_at)
--   IN_PROGRESS       a poller instance is calling OTT (lease_owner / lease_until)
--   REVOKE_OWED       saga already answered with a refund, but OTT may hold an entitlement: revoke by order
--   CANCEL_TOMBSTONE  the user's cancel arrived before the fulfil command (no command/token yet)

CREATE TABLE IF NOT EXISTS fulfilment.provisioning_task (
    order_id          VARCHAR(255) PRIMARY KEY,
    command_json      TEXT,
    reply_token_json  TEXT,
    status            VARCHAR(24)  NOT NULL
                      CHECK (status IN ('RETRYING','IN_PROGRESS','REVOKE_OWED','CANCEL_TOMBSTONE')),
    cancel_requested  BOOLEAN      NOT NULL DEFAULT FALSE,
    cancel_reason     VARCHAR(300),
    ott_uncertain     BOOLEAN      NOT NULL DEFAULT FALSE,
    last_reason       VARCHAR(40),
    last_detail       VARCHAR(300),
    attempts          INT          NOT NULL DEFAULT 1,
    next_attempt_at   TIMESTAMPTZ,
    deadline_at       TIMESTAMPTZ  NOT NULL,
    lease_owner       VARCHAR(64),
    lease_until       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_provisioning_task_due
    ON fulfilment.provisioning_task (next_attempt_at) WHERE status IN ('RETRYING','REVOKE_OWED');
CREATE INDEX IF NOT EXISTS ix_provisioning_task_deadline
    ON fulfilment.provisioning_task (deadline_at);
CREATE INDEX IF NOT EXISTS ix_provisioning_task_lease
    ON fulfilment.provisioning_task (lease_until) WHERE status = 'IN_PROGRESS';
