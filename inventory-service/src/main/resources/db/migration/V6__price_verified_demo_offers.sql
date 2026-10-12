-- V6: inventory rows for the physical demo offers seeded in catalog (CatalogSeeder).
-- Since order-service verifies the price against catalog (a client can no longer pick
-- its own amount), the billing demo triggers need offers that are *priced* at the
-- trigger values. Large stock so repeated e2e runs never exhaust them.
--   ACC_CHARGER_30W   499  → normal PHYSICAL_GOOD happy path
--   ACC_DEMO_DECLINE  1500 → PAY_NOW authorize declines (> 999); BILL_TO_MOBILE exceeds
--                            the default 1000 credit limit
--   ACC_DEMO_HARDDECLINE 777 → PAY_NOW authorizes, then capture (the pivot) hard-declines
--   ACC_DEMO_DELIVERYFAIL 499 → "FAIL" in the code: fulfil fails after the pivot →
--                               forward recovery (refund/reverse + release) → CANCELLED_REFUNDED

INSERT INTO inventory.inventory_items (offer_code, type, total, reserved, allocated) VALUES
    ('ACC_CHARGER_30W',      'PHYSICAL_GOOD', 100000, 0, 0),
    ('ACC_DEMO_DECLINE',     'PHYSICAL_GOOD', 100000, 0, 0),
    ('ACC_DEMO_HARDDECLINE', 'PHYSICAL_GOOD', 100000, 0, 0),
    ('ACC_DEMO_DELIVERYFAIL','PHYSICAL_GOOD', 100000, 0, 0)
ON CONFLICT (offer_code) DO NOTHING;
