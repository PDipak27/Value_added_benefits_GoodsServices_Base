-- Fulfilment service application tables (schema: fulfilment).
-- One row per fulfilment, written when a FulfilOrderCommand succeeds. Records the
-- delivery artifact (exactly one of tracking_ref / activation_key / external_ref,
-- per product_type) and is the handle the CancelFulfilmentCommand compensation
-- acts on.

CREATE SCHEMA IF NOT EXISTS fulfilment;

CREATE TABLE IF NOT EXISTS fulfilment.fulfilments (
    fulfilment_ref VARCHAR(64)  PRIMARY KEY,
    order_id       VARCHAR(64)  NOT NULL,
    product_type   VARCHAR(20)  NOT NULL CHECK (product_type IN ('PHYSICAL_GOOD','DIGITAL_SUBSCRIPTION','SOFTWARE_LICENSE')),
    status         VARCHAR(16)  NOT NULL DEFAULT 'FULFILLED' CHECK (status IN ('FULFILLED','CANCELLED')),
    tracking_ref   VARCHAR(100),   -- PHYSICAL_GOOD
    activation_key VARCHAR(100),   -- SOFTWARE_LICENSE
    external_ref   VARCHAR(100),   -- DIGITAL_SUBSCRIPTION
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fulfilments_order_id ON fulfilment.fulfilments (order_id);
