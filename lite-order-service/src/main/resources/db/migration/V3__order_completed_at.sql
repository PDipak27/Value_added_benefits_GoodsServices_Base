-- V3: payment-mode flows (DD-23). Orders now reach a terminal COMPLETED state
-- after fulfilment and (PAY_NOW) capture; CONFIRMED becomes an intermediate
-- state. The delivery artifact populates at completion rather than confirmation.

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;
