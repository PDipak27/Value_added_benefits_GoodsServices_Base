# Product-Types Redesign — Change Proposal (RFC)

> **Status: IMPLEMENTED, then partly superseded by DD-23.** This RFC is kept as the
> historical record of the product-types redesign (three types + a `fulfilment-service`),
> which shipped as designed. A later change — **DD-23 (payment-method-driven flows)** —
> revises several decisions here; where they conflict, **DD-23 wins**:
> - **`DIGITAL_SUBSCRIPTION` is now finite** (a seeded inventory count), not infinite —
>   so **reserve/allocate is no longer skipped for digital** (this reverses Q3 below and
>   the "infinite / no row" rows in §1, §3.3, §4).
> - The saga now has **two flows by `billingMode`**: `PAY_NOW` (reserve → authorize →
>   commit → **capture (pivot) → confirm** → fulfil → complete) and `BILL_TO_MOBILE`
>   (checkAccountLimit → allocate → **appendLedger (pivot) → confirm** → fulfil →
>   complete). The **charge is the pivot** and the order is `CONFIRMED` right after it
>   (**DD-26**): everything before it compensates LIFO, everything after it is
>   forward-only — so the per-type compensation matrix in §4 no longer applies past the
>   pivot. A PAY_NOW **hard capture decline** is the pivot failing (`withFailure`) →
>   rollback → `FAILED`; a non-transient post-pivot fulfil failure **forward-recovers**
>   to `CANCELLED_REFUNDED` — see DD-26.
> - Terminal status is **`COMPLETED`**; `CONFIRMED` is an intermediate milestone.
> - `billing-stub-service` was **renamed `billing-service`** and gained the postpaid
>   account + next-cycle ledger.
>
> The product-type *structure* (one shared `ProductType`, catalog-authoritative,
> single `fulfil` step delegated to `fulfilment-service`) is unchanged. Read DD-23
> alongside this document.

## 1. Motivation & scope

Replace the current inventory/catalog taxonomy (`DIGITAL` / `PHYSICAL` / `SLOT`)
with three **product types**, and make *fulfilment* diverge by type:

| Product type | Inventory | Fulfilment (post-payment) | Delivery artifact |
|---|---|---|---|
| `PHYSICAL_GOOD` | **finite** (stock count) | create a physical **shipment** | tracking ref |
| `DIGITAL_SUBSCRIPTION` | **infinite** (no cap) | **provision** OTT entitlement *(exists — Design/06)* | externalRef |
| `SOFTWARE_LICENSE` | **finite** (key pool) | **allocate an activation key** | activation key |

**Removed:** the `SLOT` type and everything specific to it (`serviceCenter`,
`REPAIR_*` offers, the `NO_SLOTS_AVAILABLE` reason).

This is bigger than a rename: today the saga is fulfilment-blind (reserve →
authorize → capture → confirm, same path for every offer). The redesign makes the
**fulfilment step branch by product type**, which ripples through the catalog model,
the inventory model, the shared event contracts, the saga, the read model, and
notifications.

---

## 2. The core tension — who owns "product type"? (revisits DD-21)

DD-21 just made **inventory** the authority on type (the saga sends a type-agnostic
`reserve(offerCode, qty)` and inventory resolves the type from the stored item).
That worked because *every* offer had an inventory row.

**That assumption breaks here:** `DIGITAL_SUBSCRIPTION` is *infinite* — it has no
finite inventory row to resolve a type from. And type now also drives **fulfilment**
(a saga/order concern) and **display/eligibility** (a catalog concern), not just
reservation. So type can no longer live *only* in inventory.

**Conclusion:** product type is a property of the **offer/product definition**
(catalog), and must travel to the order so the saga can branch. This partially
reverses DD-21 — inventory stops being the sole owner of type; it becomes one
consumer of a type defined upstream. We should record that as a new DD (supersedes
the relevant part of DD-21) rather than silently contradicting it.

How type reaches the saga — **decided Q1(b)+verify** (see §5): the client sends
`productType` in the place-order request (the app builds the order from an offer, so
it knows the type), and order **verifies it against catalog** at placement before
stamping the resolved value on the order + saga data. Catalog stays authoritative;
the write path avoids a blocking order→catalog dependency.

---

## 3. Component-by-component change inventory

### 3.1 shared-events (the contract — do first)
- **New** `ProductType` enum (`PHYSICAL_GOOD`, `DIGITAL_SUBSCRIPTION`, `SOFTWARE_LICENSE`),
  shared so catalog / order / inventory / fulfilment all agree on one vocabulary.
- `ReserveInventoryCommand` — for `SOFTWARE_LICENSE` the **reply must carry the
  allocated activation key**, not just a `reservationId`. Options: extend
  `InventoryReserved` with an optional `activationKey`, or a license-specific reply.
- **New fulfilment commands/replies** (shape depends on Q2):
  - digital → reuse provisioning (`EntitlementProvisioned` / `…Failed`, Design/06).
  - license → `IssueLicenseKey` → `LicenseKeyIssued` (carries key) / `…Failed`.
  - physical → `CreateShipment` → `ShipmentCreated` (carries tracking) / `…Failed`,
    plus a `CancelShipment` compensation.
- `OrderConfirmed` — extend with the **fulfilment artifact** (one of tracking ref /
  activation key / externalRef) + the `productType`, so the read model and
  notification can render the right thing.

### 3.2 catalog-service
- `Offer.category` (free `String`) → `Offer.productType` (`ProductType` enum). This
  is the authoritative source of type.
- Drop SLOT-only concept; `serviceCenter` was inventory-side only, so catalog is
  mostly a field rename + seed change.
- `CatalogSeeder` — reseed 6–8 offers across the three new types (no SLOT). Because
  the seeder skips a non-empty collection, the `offers` collection must be dropped to
  re-seed (same caveat as last change).
- Eligibility is unaffected (plan/region/device-age/KYC are orthogonal to type).

### 3.3 inventory-service
- `InventoryType` enum → align to product types, or keep an inventory-local enum with
  only the **finite** kinds (`PHYSICAL_GOOD`, `SOFTWARE_LICENSE`). Digital subs have
  **no inventory row**.
- Remove `SLOT` + `serviceCenter` from `InventoryItem` and the seed.
- **Software-license keys** need modelling: a finite pool of *individual keys* per
  offer (a `license_keys` table: `key, offerCode, status, reservationId`), because
  reserve must hand back a *specific* key. This is richer than a bare count.
  (`PHYSICAL_GOOD` stays a simple count.)
- Reserve handler: `PHYSICAL_GOOD` → decrement count; `SOFTWARE_LICENSE` → allocate a
  free key row and return it; **digital → not called** (see Q3).
- Release/compensation: physical → restock count; license → return the key to the
  pool (status back to `FREE`).
- Flyway `V3__product_types.sql`: drop SLOT seed rows, add `license_keys`, reseed.

### 3.4 order-service (saga + command + read model)
- `PlaceOrderCommand` / `Order` / `PlaceOrderSagaData` carry `productType` (+ the
  fulfilment artifact once known).
- **Saga stays one linear orchestrator** (decision Q2(ii)); the branch lives inside
  `fulfilment-service`, not the saga:
  1. Reserve inventory — **skipped for digital** (one guard); finite reserve for
     physical/license.
  2. Authorize billing — unchanged.
  3. **Fulfil — single step / single command** to `fulfilment-service`, which
     dispatches internally: provision (digital) / issue key (license) / create
     shipment (physical). Compensation is one `CancelFulfilment`, routed by type.
  4. Capture billing — unchanged.
  5. Confirm — record the fulfilment artifact on the aggregate.
- `OrderStatus` — `PROVISIONING` already exists; consider a generic `FULFILLING`
  (or per-type `ISSUING_LICENSE` / `CREATING_SHIPMENT`) for honest status reporting.
- `OrderView` (read model) — add `productType` + a `fulfilment` sub-document
  (`{ type, trackingRef? , activationKey?, externalRef? }`). The projector maps it
  from the enriched `OrderConfirmed`.

### 3.5 Fulfilment — provisioning, license keys, delivery (the new surface)
- **Digital provisioning** already designed (Design/06) but **not yet implemented**
  as a saga step — this redesign is the moment to wire it.
- **Software license** issuance — owned by inventory (it holds the keys) *or* a
  dedicated license service (Q2/Q4).
- **Physical delivery** — owned by `fulfilment-service` via an **internal delivery
  stub** (mirrors `billing-service`, proves the network boundary) rather than a separate
  `delivery-stub-service` (Q2 decision keeps service count down). The saga *initiates*
  a shipment through the single `fulfil` step; actual delivery (shipped → delivered)
  is an async lifecycle **outside** the saga (Q5).

### 3.6 notification-service
- Templates diverge by type: physical → "your order ships, tracking X"; license →
  "your activation key: …"; digital → "your subscription is active". Driven off the
  enriched `OrderConfirmed`.

### 3.7 Docs / tests
- New DD (supersedes DD-21's "inventory owns type"); update Design/02 (inventory +
  catalog), Design/04 (saga branching + per-type compensation), Design/07 (drop SLOT,
  add delivery service), README service table.
- `testing/TEST-DATASETS.md` + Postman: new offers, per-type happy paths, key-pool
  exhaustion, physical restock-on-compensation, digital "no inventory step".

---

## 4. Per-type compensation matrix (LIFO)

| Type | Reserve | Fulfil | On later failure (compensate) |
|---|---|---|---|
| `PHYSICAL_GOOD` | decrement stock | create shipment | cancel shipment → restock → refund |
| `DIGITAL_SUBSCRIPTION` | *(none)* | provision entitlement | revoke entitlement → refund |
| `SOFTWARE_LICENSE` | allocate key | (key allocation *is* the fulfilment) | return key to pool → refund |

Note billing ordering (Design/04): authorize → fulfil → capture, so a fulfilment
failure happens **before** capture and needs no refund — only the fulfilment + the
inventory are undone.
> **Superseded by DD-23/DD-26.** The matrix above is the original RFC fulfil-then-capture
> ordering. Under DD-26 the **charge is the pivot** (capture / appendLedger) with `confirm`
> right after, and everything past it is forward-only — so this per-type *compensation*
> matrix no longer applies past the pivot. A capture decline rolls back to `FAILED`
> (nothing charged); a non-transient post-pivot fulfil failure **forward-recovers** to
> `CANCELLED_REFUNDED` (refund/reverseLedger → release).

---

## 5. Decisions (resolved 2026-06-12)

**Q1 — How does `productType` reach the saga? → DECIDED: (b) client sends it +
order verifies against catalog.**
The mobile app builds the order *from an offer*, so it already knows the type —
sending `productType` in the place-order request is honest and avoids a blocking
order→catalog call on the write path. The client contract changes (acceptable —
dev mode). **Guard:** order still resolves/verifies the type against catalog at
placement (cheap cached lookup) and **persists the resolved value**, so catalog
remains the source of truth and a stale/wrong client can't steer the order down the
wrong fulfilment branch. Net: client sends → order verifies → order stamps resolved
type on the aggregate + saga data.

**Q2 — How does the saga branch fulfilment? → DECIDED: (ii) one saga + a
`fulfilment-service` participant.**
The `PlaceOrderSaga` stays one linear orchestrator —
`reserve → authorize → fulfil → capture → confirm` — where **`fulfil` is a single
step / single command** (`Fulfil` → `Fulfilled` / `FulfilmentFailed`). The
type→action dispatch (provision / issue-key / create-shipment) lives *inside*
fulfilment-service, hidden from the saga, exactly as inventory now hides
LICENSE/PHYSICAL behind one reserve. This is **not a second saga** — fulfilment-
service is just another participant like inventory and billing.
*Why over (i) conditional steps:* extensibility. A new category = a new handler
inside fulfilment-service; the saga and its **compensation matrix don't change
shape** (compensation is one `CancelFulfilment`, routed internally by type). With
`.when()` steps, every new type edits the orchestrator and multiplies branch +
compensation entries. Visibility is preserved (still one orchestrator).
*Service-count note:* fulfilment-service itself calls the OTT provisioning endpoint
(digital), inventory key allocation (license), and an **internal delivery stub**
(physical) — so no separate `delivery-stub-service` is spawned; delivery is an
internal concern of fulfilment-service.

**Q3 — Do digital subs touch inventory at all? → DECIDED: skip reserve for digital.**
No inventory row, nothing to reserve. One isolated guard on saga step 1
(`if DIGITAL_SUBSCRIPTION → skip reserve`). Reinforced by Q1: the saga knows the
type before step 1.

**Q4 — Where do software-license keys live? → DECIDED: inventory owns the key pool.**
Keys *are* the finite resource; reserve = allocate a specific key, returned in the
reply. A separate license service is only worth it if keys get a real lifecycle
(rotation, vendor callouts) — not now.

**Q5 — Does the saga wait for physical delivery? → DECIDED: no.**
Saga confirms when the shipment is *created* (fast). Shipped / delivered are a
separate async lifecycle with their own events; order is `CONFIRMED` at hand-off,
not at doorstep — otherwise a billing authorization stays open for days. Recorded as
a DD.

**Q6 — Capture timing for physical goods. → DECIDED: capture stays in the saga**
(on shipment *created*). Moving capture to the async delivery lifecycle is a bigger
change, deferred.

**Q7 — Migration of existing data. → DECIDED: dev only, assume none.**
Catalog reseed drops the `offers` collection; inventory gets a new Flyway migration.
No in-flight SLOT orders to migrate.

---

## 6. Resolved backbone (summary)

- **One shared `ProductType`**, **catalog authoritative but client-sent +
  order-verified**, **type stamped on the order**, **one linear saga with a single
  `fulfil` step delegated to a `fulfilment-service`** that dispatches by type,
  **inventory only for the two finite types** (physical count + license key pool),
  **fulfilment-service holds an internal delivery stub for physical**,
  **notification renders per type**. That's Q1(b)+verify, Q2(ii), Q3-skip,
  Q4-inventory, Q5-no-wait, Q6-capture-in-saga.
- The redesign is a good moment to **collapse the dual taxonomy** (catalog `String`
  category vs inventory enum) that DD-21 left half-unified — make them one enum.
- The only genuinely *new* build surface is **physical delivery** (internal stub) and
  **license-key modelling**; digital provisioning is already designed and just needs
  wiring as the digital branch inside fulfilment-service.
- Saga complexity is contained: **one `fulfil` branch point** (inside fulfilment-
  service, not scattered in the orchestrator) and **one compensation per step**, so
  the failure matrix does not multiply as types are added.

**All blocking decisions resolved.** Ready to implement on request; next step is
sequencing (shared-events contract first, then catalog, inventory, fulfilment-
service, saga, read model, notification, docs/tests).
