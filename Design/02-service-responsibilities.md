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

**Store:** MongoDB (`vab_catalog`, collection `offers`) — see DD-16. Offers are polymorphic across the three product types (`PHYSICAL_GOOD` / `DIGITAL_SUBSCRIPTION` / `SOFTWARE_LICENSE`, see Design/09) and their eligibility dimensions evolve often, so a document model fits better than a relational table of mostly-null columns. Each offer carries its `productType` (the shared `ProductType` vocabulary). Seeded on startup by `CatalogSeeder` when empty.

**Cache:** read-heavy and write-rare (≈ twice/week), so reads are served through a **two-tier** cache — an in-process **Caffeine L1** in front of a shared **Redis L2** (DD-17 / DD-18). L1 spares the hot offer-browse from re-deserializing ~5000 documents per request; L2 backstops L1 misses without hitting Mongo. Invalidation is **local evict-on-write** (the admin endpoints above call `@CacheEvict`, clearing local L1 + shared L2) and a **Redis pub/sub broadcast** that clears peer instances' L1 near-immediately (skip-self via an instance UUID, DD-19); a 15s TTL on both tiers is the backstop. Not event-driven — the writer and the cache live in the same service. The cache is **fail-open** (DD-20): if Redis (L2) is down, reads and admin writes degrade to MongoDB instead of erroring (`CacheErrorHandler` logs + swallows, 500ms Redis timeouts).

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

**Owns:** all **three** (now finite — DD-23) product types behind one Saga-facing
contract, stored as a `total`/`reserved`/`allocated` count in `inventory.inventory_items`
(keyed by `offerCode`; `available = total - reserved - allocated`):
- `PHYSICAL_GOOD` — integer stock count (accessories)
- `SOFTWARE_LICENSE` — finite pool of individual activation keys (`inventory.license_keys`),
  whose size is mirrored by the count
- `DIGITAL_SUBSCRIPTION` — a seeded count (DD-23 made it finite; it now has a row and
  is held like any other type)

**Hold lifecycle by payment mode (DD-23):** `PAY_NOW` does a two-phase
`reserve` → `commit` (reserve is a temporary hold with a `reserved_until` expiry that
a scheduled sweeper auto-releases; commit turns reserved → allocated). `BILL_TO_MOBILE`
does a one-step `allocate`. Both move a concrete unit/key; either can be undone by `release`.

**Type-agnostic hold commands:** the hold commands carry only `(offerCode, quantity)`.
Inventory owns the `offerCode → productType` mapping and dispatches internally: a
`PHYSICAL_GOOD`/`DIGITAL_SUBSCRIPTION` moves the count; a `SOFTWARE_LICENSE` takes one
concrete key (FREE → ALLOCATED) and returns it as `activationKey` on the reply. The type
flavours the failure reason (`POOL_EXHAUSTED` for keys / `OUT_OF_STOCK` for counts).

**Saga participant commands:**
- `inventory.Reserve.v1` (`offerCode`, `quantity`) → `InventoryReserved` (carries `reservationId`, resolved `productType`, `reservedUntil`, and — for `SOFTWARE_LICENSE` — the allocated `activationKey`) | `InventoryReservationFailed`
- `inventory.Commit.v1` (`reservationId`) → promotes a PAY_NOW reservation reserved → allocated
- `inventory.Allocate.v1` (`offerCode`, `quantity`) → one-step BILL_TO_MOBILE hold (carries the same artifacts as reserve)
- `inventory.Release.v1` (`reservationId`) → returns the held units to the item and, for a license, returns the key to the FREE pool; idempotent via reservation `status`

**Reservation ledger:** each successful hold writes a row to `inventory.reservations`
(`reservationId → offerCode, quantity, licenseKey?, status, reservedUntil`), so
release/commit/compensation can act from just the `reservationId`. A scheduled
`InventoryReservationSweeper` releases `RESERVED` rows past `reserved_until`.

**Dedupe contract:** `(sagaId, stepId)` in the Tram `received_messages` table; release
additionally idempotent via the reservation `status` (`RESERVED|ALLOCATED|RELEASED`).

**Does not own:** what an order is. It only knows reservations.

**Uses:** Eventuate Tram (participant only, no ES on inventory).

---

## Billing Service

> Renamed from `billing-stub-service` (DD-23) when it gained real account state.

**Owns:** two billing flows (DD-23).
- **PAY_NOW** — simulated card billing: `Authorize`, `Capture`, `Refund`. Persists a ledger row per call. `Capture` runs *before* fulfil (DD-24) and can hard-decline.
- **BILL_TO_MOBILE** — a per-subscriber postpaid account (`billing.billing_account`:
  status, plan tier, credit limit, current-cycle balance) plus a next-cycle ledger
  (`billing.next_cycle_ledger`). `checkAccountLimit` gates a `SUSPENDED` account or an
  over-limit amount; `appendToLedger` charges the account + records a PENDING entry;
  `reverseLedger` undoes it.

**Saga participant commands:**
- `billing.Authorize.v1` → `BillingAuthorized` | `BillingDeclined`
- `billing.Capture.v1` → `BillingCaptured` | `BillingCaptureFailed` (**withFailure** at the pivot → rollback → `FAILED`; DD-26)
- `billing.Refund.v1` → `BillingRefunded`
- `billing.CheckAccountLimit.v1` → `AccountLimitOk` | `AccountLimitExceeded` (`ACCOUNT_SUSPENDED` / `CREDIT_LIMIT_EXCEEDED`)
- `billing.AppendToLedger.v1` → `LedgerAppended` (carries `entryId`)
- `billing.ReverseLedger.v1` (`entryId`) → `LedgerReversed`; idempotent via entry status

**Hardcoded rule for demos:** PAY_NOW `amount > 999 INR` → `BillingDeclined`; the default
postpaid credit limit is 1000 INR (seeded `sub-premium` = 5000, `sub-suspended` = SUSPENDED).

**Why a real service vs in-process stub:** proves network boundary. Timeout, retry on redelivery, duplicate-authorize — none are interesting without a network.

---

## Fulfilment Service

**Owns:** turning an authorized order into a delivered artifact, one
product-type at a time (Design/09, Q2(ii)). Persists one `fulfilment.fulfilments`
row per fulfilment — the audit record and the handle the cancel compensation acts on.

**Single `fulfil` step, internal dispatch:** the saga sends one
`FulfilOrderCommand` carrying the `productType`; the service routes by type so the
orchestrator never grows a step per type and new types add a branch *here*:
- `PHYSICAL_GOOD` → create a shipment (internal delivery stub) → `trackingRef`
- `DIGITAL_SUBSCRIPTION` → provision an OTT entitlement → `externalRef`
- `SOFTWARE_LICENSE` → record the activation key already allocated by inventory at reserve/allocate → `activationKey`

**Saga participant commands:**
- `fulfilment.Fulfil.v1` (`orderId`, `subscriberId`, `offerCode`, `productType`, `activationKey?`) → `OrderFulfilled` (carries `fulfilmentRef` + exactly one artifact) | `OrderFulfilmentFailed`

**Saga ordering (DD-23/DD-26):** the **charge is the pivot** (`capture` for PAY_NOW,
`appendToLedger` for BTM) and `confirm` fires right after it; fulfil runs *after*
`confirm`, so it is **forward-only**. A non-transient fulfil failure
(`OrderFulfilmentFailed`, success-outcome) does not roll back the confirmed order — it
sets `forwardRecover` and the saga moves forward: refund/reverseLedger → release →
terminal `CANCELLED_REFUNDED` (DD-26). A PAY_NOW hard capture decline is the pivot
*failing to commit* (`withFailure`): the holds roll back and the order ends `FAILED`
(nothing captured → no refund). BILL_TO_MOBILE has no capture (the cost was appended to
the ledger at the pivot).

**Does not own:** inventory counts or the key pool (inventory owns those; the key
is returned to the FREE pool by the inventory release compensation, not here).

**Uses:** Eventuate Tram (participant only).

---

## Notification Service

**Owns:** templates, dispatch routing (SMS/email/push), delivery status.

**Consumes (async, no commands inbound):**
`OrderConfirmed` (lean intermediate milestone), `OrderCompleted` (product-type-aware
copy naming the delivered artifact — DD-23), `OrderCancelled` (pre-pivot cancel —
"cancelled, not charged" SMS), `OrderCancelledRefunded` (post-pivot forward-recovery —
"cancelled and fully refunded" SMS; DD-26), `OrderFailed`

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
