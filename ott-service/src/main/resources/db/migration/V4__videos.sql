-- OTT video catalog (§A-2). Each title maps to the offer whose ACTIVE entitlement
-- unlocks streaming. No real media — the stream endpoint returns a success message.
CREATE TABLE IF NOT EXISTS ott.videos (
    id          VARCHAR(64)  PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    offer_code  VARCHAR(100) NOT NULL
);

INSERT INTO ott.videos (id, title, offer_code) VALUES
    ('vid_hotstar_ipl',     'Hotstar — IPL Final',          'OTT_HOTSTAR_3M'),
    ('vid_hotstar_series',  'Hotstar — Original Series S1', 'OTT_HOTSTAR_3M'),
    ('vid_netflix_film',    'Netflix — Feature Film',       'OTT_NETFLIX_6M'),
    ('vid_prime_doc',       'Prime — Documentary',          'OTT_PRIME_12M')
ON CONFLICT (id) DO NOTHING;

-- Demo entitlement so the §A-2 stream flow is exercisable standalone (subscriber
-- 'sub-alice' — the seeded Keycloak user — owns the Hotstar bundle). Real
-- entitlements are written by the provisioning flow (DD-27).
INSERT INTO ott.entitlements (external_ref, order_id, subscriber_id, offer_code, status, provisioned_at)
VALUES ('OTT-SEEDALICE', 'ord-seed-alice', 'sub-alice', 'OTT_HOTSTAR_3M', 'ACTIVE', now())
ON CONFLICT (order_id) DO NOTHING;
