-- V2: product-types redesign (Design/09). The order now carries the resolved
-- product_type (client-sent, catalog-verified at placement) and, once confirmed,
-- the delivery artifact — exactly one of tracking_ref (PHYSICAL_GOOD),
-- activation_key (SOFTWARE_LICENSE) or external_ref (DIGITAL_SUBSCRIPTION).

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS product_type   VARCHAR(32);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS tracking_ref   VARCHAR(100);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS activation_key VARCHAR(100);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS external_ref   VARCHAR(100);
