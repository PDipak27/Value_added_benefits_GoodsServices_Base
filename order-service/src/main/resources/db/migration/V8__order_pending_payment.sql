-- Design/15: PAY_NOW orders wait in PENDING_PAYMENT while the subscriber pays on the provider's checkout
-- page. Kept on the aggregate so a read right after placement (read-model miss) can still show the link.
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS checkout_url       TEXT;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS payment_expires_at TIMESTAMPTZ;
