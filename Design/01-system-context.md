# System Context

## What the system is

**Value Added: Benefits (VA-BAGS)** — a telecom backend that leverages an existing subscriber base (identity, KYC, billing relationship, device IMEI) to sell adjacent goods and services billed to the mobile account.

Representative catalog, across three **product types** (DD-22 / Design/09), all
**finite** since DD-23:
- **`DIGITAL_SUBSCRIPTION`** — OTT subscriptions (Netflix, Prime, Hotstar bundles); a seeded count, provisioned as an entitlement
- **`SOFTWARE_LICENSE`** — security / productivity keys (antivirus, Microsoft 365); a finite pool of activation keys
- **`PHYSICAL_GOOD`** — accessories (earbuds, power banks); a finite stock count, shipped

Each order picks a **payment mode** (DD-23): `PAY_NOW` (card-style reserve → authorize →
commit) or `BILL_TO_MOBILE` (postpaid check-limit → allocate → next-cycle ledger).

**Scale envelope:** ~50 order TPS (writes), ~300 RPS (reads). Comfortably a single-node Postgres workload — none of CQRS/read-store/CDC is load-bearing for throughput. Each pattern is justified by *capability* (distributed transaction, strategic event log, read-shape), not scale. See DD-14.

---

## System context diagram

```mermaid
flowchart LR
    User([Subscriber\nmobile/web])
    Ops([Ops / Back-office])

    subgraph VAB["VA-BAGS"]
        GW[API Gateway / BFF]
        CAT[Catalog & Eligibility]
        ORD[Order Service\nCQRS + Saga]
        INV[Inventory Service]
        BIL[Billing Service]
        FUL[Fulfilment Service]
        NOT[Notification Service]
    end

    subgraph EXT["Third-party (separate codebase)"]
        OTT[OTT Platform\nSpring Boot]
    end

    BROKER[(Kafka\nevent log)]
    WSTORE[(PostgreSQL\norder state + outbox)]
    RSTORE[(MongoDB\nread projections)]

    User -->|HTTPS REST| GW
    Ops  -->|HTTPS REST| GW

    GW -->|sync REST| CAT
    GW -->|cmd sync / status sync| ORD
    GW -.->|OIDC OP endpoints| OTT

    ORD --> WSTORE
    ORD <-->|events + commands| BROKER
    INV <-->|events + commands| BROKER
    BIL <-->|events + commands| BROKER
    FUL <-->|events + commands| BROKER
    NOT <---|domain events| BROKER
    CAT <---|domain events| BROKER

    BROKER -->|projector consumer| RSTORE
    ORD -->|Query API: primary| RSTORE
    ORD -.->|read-your-writes fallback| WSTORE

    FUL -->|provisioning REST\nclient-credentials OAuth2| OTT
    OTT -->|userinfo verify| GW
```

---

## Sync vs async edge matrix

| Edge | Mode | Reason |
|---|---|---|
| Client → Gateway | Sync HTTPS | User is waiting |
| Gateway → Catalog (browse/eligibility) | Sync + cache | Read-only, latency-critical |
| Gateway → Order command | Sync request → `202 Accepted` | Decouple submission ack from fulfillment |
| Gateway → Order Query API | Sync | Fast denormalized read |
| Order Saga ↔ Inventory / Billing / Fulfilment | **Async Kafka** | Long-lived, retryable, participant restarts safe |
| Fulfilment → OTT provisioning | Sync REST wrapped in the async fulfil step | External REST contract; the Saga step itself is async |
| Domain events → Notification, Projector | **Async pub/sub** | Must never block writers |
| Cross-service queries | **Forbidden** | Sync cross-service reads = gateway drug to distributed monolith |

---

## Key invariants

1. No service reads another service's database.
2. No business logic in the Gateway.
3. The read side serves from MongoDB by default; its **only** permitted read of the write-side Postgres is the bounded read-your-writes fallback (lookup by `orderId` during projection lag). No JOINs, no list/scan queries against the write store.
4. Every mutating boundary uses the Eventuate CDC outbox (Tram `message` table) — no direct Kafka publish from application code.
