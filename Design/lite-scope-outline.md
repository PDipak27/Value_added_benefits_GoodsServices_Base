# Lite Scope Outline — VA-BAGS Lite

**Purpose:** a trimmed "Lite" edition of VA-BAGS built for **CI/CD + deployment
practice** (interview prep), not feature completeness. Target: **api-gateway +
saga orchestrator + 2 participants ≈ 4 services**, small enough to fit a laptop
*and* run cheaply on a small EC2. Happy-path e2e only.

> Doc-naming rule for this workstream: every Lite doc filename starts with `lite`.

---

## 1. Target roster (4 services)

| Service | Role | Status |
|---|---|---|
| **api-gateway** | Stateless router / edge | **Keep** (Redis rate-limiter off; auth off via `lite` profile) |
| **lite-order-service** | Saga **orchestrator** (`PlaceOrderSaga`), command + read | **Keep** (CQRS read collapsed to Postgres) |
| **lite-inventory-service** | Participant — reserve / commit / **release** (compensation) | **Keep** |
| **lite-billing-service** | Participant — authorize / **capture (pivot)** / refund | **Keep** |
| catalog-service | Offer lookup (Mongo + Redis cache) | **Drop** → stub in order |
| fulfilment-service | Forward-only tail; OTT + Keycloak-token client | **Drop** → `invokeLocal` stub |
| notification-service | Post-completion notify | **Drop** → log-only / omitted |
| ott-service | External OTT provider | **Drop** (dies with fulfilment) |

---

## 2. Chosen participants: **Inventory + Billing** (drop Fulfilment)

Saga channels are exactly three: `inventoryService`, `billingService`,
`fulfilmentService`.

| Participant | Saga role | Why keep / drop |
|---|---|---|
| **Billing** | The **pivot** (capture) + refund | The single most important saga concept — the go/no-go commit point. Keep. |
| **Inventory** | reserve → commit, compensating **release** | Canonical compensatable hold + LIFO rollback. Keep. |
| **Fulfilment** | forward-only tail; OTT + notification | Only fires on *failure*; happy-path e2e never exercises it. Heaviest (OTT/Keycloak). **Drop** — takes ott + notification with it. |

### Fulfil-step decision: **(a) `invokeLocal` stub inside `PlaceOrderSaga`**
No deployed fulfilment participant. The fulfil step becomes a local step that
returns a synthetic `fulfilmentRef` and lets the saga proceed to `COMPLETED`.
Holds the line at **4 services** and collapses all three stub asks
(fulfilment / OTT / notification) into one trivial local step.

---

## 3. Realignment decisions (what "Lite" means)

| Full feature | Lite move | Rationale |
|---|---|---|
| **Mongo CQRS read side** (order query/projection) | Collapse read to the **Postgres** write model via JPA; delete projector + mongo views | Drops Mongo; order status still queryable for e2e |
| **catalog-service** (offer lookup via `CatalogClient`) | **Stub** — client supplies price/productType, or in-memory offer table in order | Drops catalog + its Mongo + Redis cache |
| **fulfilment + ott + notification** | **One `invokeLocal` stub** (success + fake ref); no notification consumer (log-only) | Drops 3 services + OTT/Keycloak-token client |
| **api-gateway Redis rate-limiter** | Disable the filter → stateless router | Drops Redis |
| **Keycloak / OAuth2 / OIDC** | `permitAll` via a `lite` Spring profile; gateway = plain router | Drops Keycloak; toggleable back on |
| **BILL_TO_MOBILE billing mode** | Run **PAY_NOW only** for Lite | Halves the saga; Inventory=reserve/commit/release, Billing=authorize/capture/refund |

---

## 4. Lite happy-path saga (PAY_NOW)

```
reserve(inv) → authorize(bill) → commit(inv) → CAPTURE(bill = pivot)
             → confirm → fulfil(local stub → ok) → COMPLETED
```

Still demonstrates: orchestration, async command/reply over Kafka,
**transactional outbox via eventuate-cdc**, the pivot, per-service Postgres DBs,
LIFO compensation on the pre-pivot steps.

---

## 5. Footprint

| Tier | Full | Lite |
|---|---|---|
| App services | 8 | **4** — api-gateway, lite-order, lite-inventory, lite-billing |
| Infra | 7 (postgres, mongo, redis, kafka, zookeeper, cdc, keycloak) | **3** — Postgres, **Kafka (KRaft, no ZooKeeper)**, eventuate-cdc |
| Containers | ~15 | **~7** |
| RAM (tuned) | ~10–12 GB | **~2 GB** |

---

## 6. Build & module layout (decision)

**Keep the root `pom.xml` canonical — do NOT rename it to `prelite-pom.xml`.**
Child modules declare their parent by coordinates only (no `<relativePath>`), so
Maven defaults to `../pom.xml`; renaming the parent breaks parent resolution for
every child and also breaks IDE import, bare `mvn`, Docker builds, and CI.

**Preserve the pre-lite (full) build with a git tag instead:**

```
git tag pre-lite-full        # freezes the full 8-service build; retrievable anytime
```

**Then transform in place (no duplication):**

- `git mv order-service lite-order-service` (+ inventory, billing); update each
  `artifactId` to `lite-*`.
- Drop `catalog-service`, `fulfilment-service`, `notification-service`,
  `ott-service` from the root `<modules>` list (dirs recoverable via the tag).
- Root aggregator keeps coordinates `com.vab:va-bags:0.1.0-SNAPSHOT`; its
  `<modules>` now lists: `shared-events`, `shared-observability`, `api-gateway`,
  `lite-order-service`, `lite-inventory-service`, `lite-billing-service`,
  `e2e-tests`.
- **Reuse** `shared-events` and `shared-observability` as-is — no `lite-` prefix.
- **api-gateway**: keep the name; toggle Lite behaviour via a `lite` Spring
  profile (routes/security differ, code is shared). Rename to `lite-api-gateway`
  only if you want naming symmetry — optional.

Naming verdict: `lite-order-service` / `lite-inventory-service` /
`lite-billing-service` are good (image names, k8s deployments, gateway routes all
read unambiguously "lite"). The only rejected item is the `pom.xml` rename.

---

## 7. Interview talking points (the trims become answers)

- "Full build is CQRS with a Mongo read model; Lite collapses read/write to
  Postgres to shrink the deploy footprint — I can speak to both."
- "Two Spring profiles — `secured` (Keycloak/OIDC) and `lite` (auth off) —
  toggled per environment."
- "Moved Kafka to KRaft, dropped ZooKeeper entirely."
- "Stubbed the forward-only fulfilment tail since happy-path e2e never exercises
  forward-recovery."
- "Used a git tag to snapshot the full build before trimming — kept `pom.xml`
  canonical rather than forking the aggregator."

---

## 8. Open items / next steps

- [ ] Confirm build layout (git-tag + in-place transform) vs. keeping both
      aggregators coexisting in one tree.
- [ ] Decide gateway name: `api-gateway` + `lite` profile (recommended) vs.
      `lite-api-gateway`.
- [ ] Produce the concrete per-service cut-list (files/modules to remove vs stub).
