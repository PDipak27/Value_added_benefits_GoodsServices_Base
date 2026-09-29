-- V2: BILL_TO_MOBILE support (DD-23).
--   * billing_account — per-subscriber postpaid account (status, plan, credit limit).
--   * next_cycle_ledger — charges parked on the subscriber's next cycle, with a
--     PENDING|REVERSED lifecycle so saga compensation can reverse them.

CREATE TABLE IF NOT EXISTS billing.billing_account (
    subscriber_id          VARCHAR(64)  PRIMARY KEY,
    status                 VARCHAR(16)  NOT NULL,   -- ACTIVE | SUSPENDED
    plan_tier              VARCHAR(16)  NOT NULL,    -- e.g. BASIC
    credit_limit           BIGINT       NOT NULL,
    current_cycle_balance  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT billing_account_status_check CHECK (status IN ('ACTIVE','SUSPENDED'))
);

CREATE TABLE IF NOT EXISTS billing.next_cycle_ledger (
    id             VARCHAR(64)  PRIMARY KEY,
    order_id       VARCHAR(64)  NOT NULL,
    subscriber_id  VARCHAR(64)  NOT NULL,
    amount         BIGINT       NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    status         VARCHAR(16)  NOT NULL,   -- PENDING | REVERSED
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT next_cycle_ledger_status_check CHECK (status IN ('PENDING','REVERSED'))
);

CREATE INDEX IF NOT EXISTS idx_next_cycle_ledger_subscriber ON billing.next_cycle_ledger (subscriber_id);
CREATE INDEX IF NOT EXISTS idx_next_cycle_ledger_order      ON billing.next_cycle_ledger (order_id);

-- Seed a couple of explicit accounts for demos; unknown subscribers are
-- auto-provisioned ACTIVE/BASIC/1000 on first check.
INSERT INTO billing.billing_account (subscriber_id, status, plan_tier, credit_limit) VALUES
    ('sub-suspended', 'SUSPENDED', 'BASIC',   1000),
    ('sub-premium',   'ACTIVE',    'PREMIUM', 5000)
ON CONFLICT (subscriber_id) DO NOTHING;
