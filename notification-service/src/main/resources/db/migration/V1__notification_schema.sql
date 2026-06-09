-- Notification service application tables (schema: notification)

CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE IF NOT EXISTS notification.delivery_log (
    id            VARCHAR(64)  PRIMARY KEY,
    order_id      VARCHAR(64),
    type          VARCHAR(32)  NOT NULL,   -- ORDER_CONFIRMED | ORDER_FAILED
    channel       VARCHAR(16)  NOT NULL,   -- SMS | EMAIL | PUSH
    status        VARCHAR(16)  NOT NULL,   -- SENT | FAILED
    provider_ref  VARCHAR(64),
    body          TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_delivery_log_order_id ON notification.delivery_log (order_id);
