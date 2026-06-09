# Service Responsibilities

## API Gateway / BFF

**Owns:** TLS termination, JWT validation, rate limiting, request routing, response shaping per client version, OIDC Provider endpoints (`/authorize`, `/token`, `/userinfo`, `/.well-known/openid-configuration`, `/jwks`).

**Does not own:** business rules, order state, inventory counts, any domain data.

**Exposes:** REST to external clients only.

---

## Catalog & Eligibility Service

**Owns:** offer definitions, price snapshots, eligibility rules (plan tier, region, device age, KYC level).

**Exposes (read):**
- `GET /v1/offers` — server-side eligibility-filtered list
- `GET /v1/offers/{offerCode}` — offer detail with `priceSnapshotId`
- `POST /v1/offers/{offerCode}:evaluate` — detailed eligibility result

**Exposes (admin/write):**
- `POST /v1/offers` — create + publish an offer
- `PUT /v1/offers/{offerCode}` — replace an offer (price/eligibility)
- `POST /v1/offers/{offerCode}:withdraw` — withdraw an offer

**Emits events:** `OfferPublished`, `OfferWithdrawn`, `PriceChanged` — *deferred*; intended for a cross-service consumer (Order projector, so historical orders carry a price snapshot, not a live FK). **Not** used for cache invalidation — see DD-17.

**Store:** MongoDB (`vab_catalog`, collection `offers`) — see DD-16. Offers are polymorphic across categories (DIGITAL / PHYSICAL / SLOT) and their eligibility dimensions evolve often, so a document model fits better than a relational table of mostly-null columns. Seeded on startup by `CatalogSeeder` when empty.

**Cache:** read-heavy and write-rare (≈ twice/week), so reads are served through a shared **Redis** cache (DD-17). Invalidation is **local evict-on-write** (the admin endpoints above call `@CacheEvict`) plus a 15s TTL backstop — not event-driven, because the writer and the cache live in the same service.

**Does not own:** per-subscriber state, orders, inventory.

**Deployment cadence driver:** content/pricing changes weekly, independent of transaction code.

---

## Order Service *(depth service)*

Single deployable. Three internal packages — not split deploys.

```
order-service/
  command/    ← Order entity (state-stored), command handlers, JPA repo + DomainEventPublisher
  saga/       ← PlaceOrderSaga, SagaData, SagaManager bean
  query/      ← projectors, Mongo repositories, Query REST API
  idempotency/← idempotency_keys table
```

**Owns:**
- Order aggregate lifecycle (state-stored JPA + domain events via Tram transactional outbox — *not* event-sourced; see DD-14)
- Saga orchestration (Eventuate Tram Sagas)
- Event projections to MongoDB (optional read-model; reads degrade to the write store via read-your-writes — DD-15)
- Idempotency dedupe
- Read API (`GET /v1/orders/*`, `GET /v1/entitlements`)

**Does not own:** inventory counts, billing ledger, notification dispatch.

**Single deployable rationale:** CQRS is a logical split. Independent scaling is not needed yet; the package boundary preserves the option to split later.

---

## Inventory Service

**Owns:** three inventory types behind one Saga-facing contract:
- `PHYSICAL` — integer stock count (accessories)
- `SLOT` — calendar slots per service center (priority repair)
- `LICENSE` — finite pool of pre-purchased OTT seats

**Saga participant commands:**
- `inventory.Reserve.v1` → replies `InventoryReserved` | `InventoryReservationFailed`
- `inventory.Commit.v1` → replies `InventoryCommitted`
- `inventory.Release.v1` → replies `InventoryReleased`

**Dedupe contract:** `(sagaId, stepId)` in `processed_messages` table.

**Does not own:** what an order is. It only knows reservations.

**Uses:** Eventuate Tram (participant only, no ES on inventory).

---

## Billing Stub Service

**Owns:** simulated billing — `Authorize`, `Capture`, `Refund`. Persists a ledger row per call.

**Saga participant commands:**
- `billing.Authorize.v1` → `BillingAuthorized` | `BillingDeclined`
- `billing.Capture.v1` → `BillingCaptured`
- `billing.Refund.v1` → `BillingRefunded`

**Hardcoded rule for demos:** `amount > 999 INR` → `BillingDeclined` (triggers compensation path).

**Why a real service vs in-process stub:** proves network boundary. Timeout, retry on redelivery, duplicate-authorize — none are interesting without a network.

---

## Notification Service

**Owns:** templates, dispatch routing (SMS/email/push), delivery status.

**Consumes (async, no commands inbound):**
`OrderConfirmed`, `OrderFailed`, `EntitlementActivated`, `OrderCanceledByUser`

**Does not own:** any rule about *whether* to notify — the event is the trigger.

**Pattern demonstrated:** pure event-driven consumer, no coupling to any other service.

---

## OTT Platform *(separate codebase)*

**Owns:** video catalog (search + stream), local entitlement table.

**Two integration contracts with VA-BAGS:**
1. **OIDC Relying Party** — redirects user to Gateway's `/authorize` for login.
2. **Provisioning API** — `POST /admin/entitlements` (called by Order Saga, secured via client-credentials OAuth2).

**Uniqueness:** `(subscriberId, offerCode)` unique constraint on local entitlement table.

**Does not own:** subscriber identity, billing, plan data.

**Stack:** Spring Boot (same as VA-BAGS), separate repo, separate DB.
