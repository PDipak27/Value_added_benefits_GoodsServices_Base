-- Billing stub service application tables (schema: billing)

CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE IF NOT EXISTS billing.billing_ledger (
    id          VARCHAR(64)  PRIMARY KEY,
    order_id    VARCHAR(64),
    auth_id     VARCHAR(64),
    type        VARCHAR(16)  NOT NULL,   -- AUTHORIZE | CAPTURE | REFUND
    status      VARCHAR(16)  NOT NULL,   -- AUTHORIZED | DECLINED | CAPTURED | REFUNDED
    amount      BIGINT       NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_billing_ledger_auth_id  ON billing.billing_ledger (auth_id);
CREATE INDEX IF NOT EXISTS idx_billing_ledger_order_id ON billing.billing_ledger (order_id);
