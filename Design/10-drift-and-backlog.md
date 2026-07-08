# Design — Drift Reconciliation & Pending Backlog

Iterations 1–3 designed an **Event-Sourced** system (iteration 3 closed on "Eventuate ES + Kafka"). From **DD-14** onward the build pivoted to **Eventuate Tram** (state-stored aggregate + transactional outbox). This document reconciles the original iteration 1–3 design against what is actually built, separating **intentional drift** (design changed by a later DD) from **pending backlog** (designed, never built, not superseded). Verified against code on 2026-06-23.

See also: [08-design-decisions.md](08-design-decisions.md) · [06-ott-auth.md](06-ott-auth.md) · [01-system-context.md](01-system-context.md) · [09-product-types-redesign.md](09-product-types-redesign.md).

---

## 1. Drift — designed in iter 1–3, then changed by a later DD (intentional)

| # | Iter 1–3 design | Current build | Superseded by |
|---|---|---|---|
| 1 | **Event Sourcing**: ES Order aggregate, replay-based saga, append-only event store, projector rebuild-from-store, rich envelope (`eventId`/`correlationId`/`causationId`/`aggregateVersion`/`schemaVersion`) | State-stored aggregate + Tram transactional outbox; saga state in JDBC `saga_instance`; plain Tram event POJOs via `JSonMapper` (no envelope, no replay/rebuild) | **DD-14** |
| 2 | Order Saga calls OTT `POST /admin/entitlements` as saga step 3 | Dedicated **fulfilment-service** owns post-payment fulfilment; the saga sends one `FulfilOrderCommand`, OTT is one branch inside it | **DD-22, DD-27** |
| 3 | Two-phase billing: Authorize (2) → Provision (3) → **Capture (4, after success)** | **Capture is the pivot, *before* fulfil**; post-pivot failures forward-recover to `CANCELLED_REFUNDED` | **DD-24, DD-26** |
| 4 | Inventory three flavors: Physical / **Slot (repair calendar)** / License; digital infinite | Product types PHYSICAL_GOOD / DIGITAL_SUBSCRIPTION / SOFTWARE_LICENSE (**SLOT removed**); digital finite | **DD-22, DD-23** |
| 5 | Single order flow | **Payment modes** PAY_NOW vs BILL_TO_MOBILE (postpaid check-limit → allocate → next-cycle ledger) added | **DD-23** |

> The original `correlationId`/`causationId` envelope and the "rebuild any read model by replaying the event store" capability are casualties of drift #1 — they no longer exist and are **not** on the backlog (they presuppose ES). Distributed *tracing* (a separate concern) remains pending — see C2.

---

## 2. Pending backlog — designed in iter 1–3, not built, not superseded

### Section A — Federated auth / OIDC
*OP = **self-hosted Keycloak** (DD-29); the gateway stays **Spring Cloud Gateway** as a resource server, **not** the OP. Implementation in **4 phases**: **A‑1** — stand up Keycloak (realm/clients/users via import) + secure provisioning M2M, incl. audience-restricted tokens (A1 / A4 / A6); **A‑2** — subscriber login via Keycloak, OTT as RP, gated video content (A2 / A3); **A‑3** — gateway edge JWT auth + JWT-derived subject (A5); **A‑4** — production hardening (A7): externalize secrets, TLS, Keycloak prod mode.*

- **A1. OIDC Provider — self-hosted Keycloak** (DD-29): a `keycloak` container + DB and a `vab` realm imported with clients + demo users; standard endpoints at `/realms/vab/protocol/openid-connect/*` + discovery/JWKS. The **gateway is not the OP** — it stays Spring Cloud Gateway as the edge resource server (§A5). *Status: **Phase A‑1 done** — `vab-keycloak` container (Postgres-backed, port 8088) + `vab` realm import with the `vab-provisioning` client (issuer `http://localhost:8088/realms/vab`); the OTT relying-party client + subscriber users land in A‑2. The api-gateway `oauth2-authorization-server` dependency is swapped for `oauth2-resource-server` in A‑3.*
- **A2. OTT as OIDC Relying Party** (against Keycloak): subscriber login (Authorization Code + PKCE), plus OTT's "real" surface — video catalog `GET /v1/videos`, `GET /v1/videos/{id}/stream` gated on the local entitlement. *Status: **Phase A‑2 done** — ott-service is a server-side OIDC **login client** (public `vab-ott` client, Auth Code + PKCE) — **DD-30**. `GET /v1/videos` lists the seeded catalog; `GET /v1/videos/{id}/stream` is session-protected and entitlement-gated — returns `"Playing video: …"` when entitled, **403** otherwise (no real media). A second Spring Security filter chain keeps the §A‑1 provisioning API a Bearer resource server.*
- **A3. OTT token verification**: OTT validates the subscriber's Keycloak token (id_token claims / `userinfo`) before serving gated content. *Status: **done (Phase A‑2)** — the OIDC login establishes the session; `subscriberId` comes from the id-token claim (Keycloak user-attribute mapper) and is matched against ott-service's entitlements.*
- **A4. Client-credentials-secured provisioning** *(was iter-3 design, dropped in DD-27 — now reinstated as backlog)*: secure the fulfilment → OTT call with OAuth2 client-credentials (`scope=ott:provision`) + token caching. *Status: **done (Phase A‑1).** fulfilment fetches a Keycloak client-credentials token (`KeycloakTokenProvider`, cached to ~30s before expiry) and sends it as a Bearer; **ott-service is now an OAuth2 resource server** requiring scope `ott:provision` on `/ott/v1/entitlements/**`. Both provision and revoke are authenticated, and **audience-restricted** — a Keycloak `ott-service` audience mapper puts `aud: ott-service` in the token and ott-service's `AudienceValidator` (alongside the default issuer/expiry checks) rejects tokens minted for any other resource server.*
- **A5. Gateway edge auth (Spring Cloud Gateway as resource server)**: validate Keycloak JWTs (JWKS) + secure the currently-open catalog / orders / entitlements / ops routes; derive `subscriberId` from the token instead of a header/param. *Status: **Phase A‑3 done** — DD-31. The gateway is a reactive **resource server** (JWKS via `issuer-uri`): catalog browse public, orders/entitlements require a valid token, back-office actions (retry / complete / revoke) gated on the `vab-admin` realm role. **Token relay** — the Bearer is forwarded downstream and **order-service is now a resource server too** (defence in depth), reading `subscriberId` from the token `subscriberId` claim (not `sub`, which is a Keycloak UUID; the claim is the A‑2 user-attribute). The `oauth2-authorization-server` dep is dropped for `oauth2-resource-server`.*
- **A6. OIDC scope/claim contract** — configured **in Keycloak** (client scopes + protocol mappers): claims (`sub`, `msisdn_hash`, `name`) and scopes (`openid profile`, `ott:provision`) for the OTT RP and the provisioning client. *Status: **Phase A‑1** — the `ott:provision` client scope (with the `ott-service` audience mapper) is in the realm import; the OTT RP claim mappers (`msisdn_hash`, etc.) come with A‑2. (Moved here from §C4 — federated-auth, not platform.)*
- **A7. Production hardening of the auth stack** (Phase A‑4): externalize secrets — Keycloak admin creds, the `vab-provisioning` client secret, and DB creds — to env / a secret store, removing the hardcoded values from the realm import / `docker-compose.yml` / `application.yml`; **TLS** for Keycloak and the services (`sslRequired`, no bearer tokens over plaintext); run Keycloak in **production mode** (`start`, explicit hostname/proxy config, no dev caching). *Status: none — deferred to Phase A‑4. (Audience validation, the other A‑1 hardening gap, is now implemented — see A4.)*

### Section B — Order query surface (iter 3 §2.3 contract)
- **B1. `GET /v1/entitlements` "My Benefits"** — **Implemented (Phase 1–2).** order-service serves `/v1/entitlements?subscriberId=` from a 
   new `entitlements_v1` Mongo projection (the projector activates an entitlement on `OrderCompleted` for DIGITAL_SUBSCRIPTION / SOFTWARE_LICENSE). 
   Uniqueness layer 1 (command-time 409 on an already-active offer) + layer 2 (partial unique index on active `{subscriberId, offerCode}`). 
   Benefit **validity** is threaded end-to-end: catalog `Offer.termMonths` → order snapshots it at placement (fail-open) 
   → fulfilment computes `validFrom`/`validUntil` (now + term) → stored on the OTT entitlement 
   *and* `entitlements_v1` (`validUntil` null = perpetual). 
**Admin revoke (Phase 3):** `POST /v1/orders/{id}/revoke-entitlement` (COMPLETED-only, async via Tram→fulfilment, no refund) 
  → OTT `DELETE` for DIGITAL_SUBSCRIPTION / read-model-only for SOFTWARE_LICENSE → `entitlements_v1` flips to REVOKED, freeing the uniqueness slot. 
  *Remaining: gateway routing + JWT subject (Section A).*
- **B2. `GET /v1/orders/{id}/timeline`** — **Implemented.** Standalone audit-timeline route on `OrderQueryController`, served from `OrderView.timeline` (read-model only; 404 until projected). Full detail with the same timeline embedded stays at `GET /{orderId}`.
- **B3. `order_search_v1`** — **Implemented.** A flattened, timeline-free ops projection built by a **separate** projector (`OrderSearchProjector`) on its own Kafka consumer group (`orderSearchProjector`) — so the same order event stream feeds two independently-rebuildable read shapes (orders_v1 + order_search_v1). Queryable via `GET /v1/ops/orders` (optional `status` / `offerCode` / `from` / `to` filters, newest-first, capped) through `OrderSearchService` (dynamic MongoTemplate query); secondary indexes on `status`/`offerCode`/`placedAt`. *(Gateway routing for `/v1/ops/**` is Section A.)*

### Section C — Cross-cutting / platform (iter 2/3 §7)
- **C1. DLQ per consumer group + replay tool.** *Status: none.*
- **C2. Distributed tracing / correlation**: W3C `traceparent` propagated over HTTP + Kafka headers, `correlationId`/`causationId`, OTel exporter → Loki/Grafana panels. *Status: absent in `vabags_base` (the Loki/Grafana/k6 work lives in the separate `eventuate-saga` repo, not here).*
- **C3. Schema-registry-backed event versioning** *(was iter-2/3 design — now backlog)*: register `<aggregate>.<Event>.vN` JSON-Schemas in Apicurio with additive-only evolution. *Status: the Apicurio container runs but is **unused** — events are plain Java classes serialized via `JSonMapper`.*
- **C4. Richer eligibility-rule shape** (catalog): eligibility beyond the current plan-tier / region / device-age / KYC dimensions. *Status: deferred since iter 3 §10.* *(The OIDC scope/claim contract previously bundled here moved to §A6 — it's federated-auth, not platform.)*

### Section D — Smaller deferrals (from the DD log)
- **D1. Catalog domain events + price-snapshot consumer** — **deliberately not built (not an oversight).** The *correctness* goal ("historical orders carry a snapshot, not a live FK") is **already satisfied**: an order copies `amount`/`currency` at placement and is immune to later catalog edits. The remaining pieces are unneeded here — snapshotting the display *name* is **cosmetic** (no UI consumes it; e2e asserts status, not names), and the catalog **event mechanism** (`OfferPublished` / `OfferWithdrawn` / `PriceChanged` → cross-service mirror) is only justified by a real consumer, which doesn't exist (DD-17 deemed its Mongo-publication infra not worth the cost). *Revisit only if a UI needs frozen names or a consumer needs a live catalog mirror. Ref: DD-17 / Design/02.*
- **D2. `abandon → CANCELLED_REFUNDED` escape hatch** from `FULFILMENT_FAILED`. *Deferred: DD-27.*

---

## 3. Built & matches the iter 1–3 intent
Idempotency — client-supplied key + `idempotency_keys` table (DD-07); single-deployable CQRS order-service (DD-06); saga orchestration + LIFO compensation + forward-recovery (now via Tram, not ES); Mongo `orders_v1` projection with embedded timeline; gateway routing for catalog/orders; the three product types, payment modes, and OTT provisioning + park / admin re-drive (DD-27).
