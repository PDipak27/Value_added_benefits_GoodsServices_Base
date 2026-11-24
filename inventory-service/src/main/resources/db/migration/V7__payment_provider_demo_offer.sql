-- Design/15: PAY_NOW now goes through the payment provider simulator (payment-sim). A decline is the
-- subscriber pressing "Decline" on the checkout page; this offer exercises the provider being down:
--   ACC_DEMO_PSPDOWN 503 → payment-sim refuses to create the intent (503) → order FAILED, hold released.
INSERT INTO inventory.inventory_items (offer_code, type, total, reserved, allocated) VALUES
    ('ACC_DEMO_PSPDOWN', 'PHYSICAL_GOOD', 100000, 0, 0)
ON CONFLICT (offer_code) DO NOTHING;
