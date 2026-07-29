-- V5 (DD-27): inventory rows for the OTT provisioning-failure demo offers seeded
-- in catalog (CatalogSeeder). They are DIGITAL_SUBSCRIPTION, so they need a row
-- here for the saga's reserve step to succeed and reach the fulfil/OTT step —
-- where ott-service then returns 503 (OTTDOWN) / 422 (OTTBAD) and the order parks
-- in FULFILMENT_FAILED. Without these rows reserve would fail with ITEM_NOT_FOUND
-- (→ FAILED) and the OTT failure path would never be exercised.

INSERT INTO inventory.inventory_items (offer_code, type, total, reserved, allocated) VALUES
    ('OTT_OTTDOWN_1M', 'DIGITAL_SUBSCRIPTION', 100, 0, 0),
    ('OTT_OTTBAD_1M',  'DIGITAL_SUBSCRIPTION', 100, 0, 0)
ON CONFLICT (offer_code) DO NOTHING;
