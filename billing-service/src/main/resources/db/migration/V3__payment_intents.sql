-- V3: PAY_NOW through an external payment provider (Design/15).
--   * payment_intent — one row per PAY_NOW order: the provider's intent, its state, and the saga's
--     deferred reply token while the user is paying.
--   * psp_webhook_event — provider event ids already applied (webhooks are delivered at least once).

CREATE TABLE IF NOT EXISTS billing.payment_intent (
    order_id          VARCHAR(64)  PRIMARY KEY,
    intent_id         VARCHAR(64)  UNIQUE,              -- null for a cancel that arrived before the intent
    subscriber_id     VARCHAR(64),
    amount            BIGINT,
    currency          VARCHAR(3),
    status            VARCHAR(16)  NOT NULL,
    checkout_url      TEXT,
    expires_at        TIMESTAMPTZ,
    reply_token_json  TEXT,                             -- set while the saga awaits the payment
    last_error        VARCHAR(300),
    next_attempt_at   TIMESTAMPTZ,                      -- VOID_OWED / REFUND_OWED retries
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT payment_intent_status_check CHECK (status IN (
        'CREATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'EXPIRED', 'CANCELLED',
        'VOID_OWED', 'VOIDED', 'REFUND_OWED', 'REFUNDED'))
);

CREATE INDEX IF NOT EXISTS ix_payment_intent_expiry ON billing.payment_intent (expires_at) WHERE status = 'CREATED';
CREATE INDEX IF NOT EXISTS ix_payment_intent_owed   ON billing.payment_intent (next_attempt_at)
    WHERE status IN ('VOID_OWED', 'REFUND_OWED');

CREATE TABLE IF NOT EXISTS billing.psp_webhook_event (
    event_id     VARCHAR(64)  PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
