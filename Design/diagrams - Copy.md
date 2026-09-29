# VA-BAGS — All Diagrams

A single place to view every Mermaid diagram in the design set. Each block is the render-safe copy of a diagram that also lives in its source doc (labels are quoted so special characters like `{}`, `()`, `*`, `:` render in strict Mermaid). Reflects the post-Event-Sourcing design (DD-14 / DD-15).

| # | Diagram | Source |
|---|---------|--------|
| 1 | System context | [01-system-context.md](01-system-context.md) |
| 2 | Order service — internal architecture | [03-order-service-deep-dive.md](03-order-service-deep-dive.md) |
| 3 | Order placement — end-to-end sequence | [03-order-service-deep-dive.md](03-order-service-deep-dive.md) |
| 4 | PlaceOrderSaga — state machine | [04-saga-design.md](04-saga-design.md) |
| 5 | OIDC login flow (subscriber → OTT) | [06-ott-auth.md](06-ott-auth.md) |
| 6 | Entitlement provisioning (Order Saga → OTT) | [06-ott-auth.md](06-ott-auth.md) |

---

## 1. System context

How external actors, VA-BAGS services, the third-party OTT platform, and the data stores connect. Kafka is the durable event log; the read side serves from Mongo with a read-your-writes fallback to Postgres.

```mermaid
flowchart LR
    User(["Subscriber\nmobile/web"])
    Ops(["Ops / Back-office"])

    subgraph VAB["VA-BAGS"]
        GW["API Gateway / BFF"]
        CAT["Catalog & Eligibility"]
        ORD["Order Service\nCQRS + Saga"]
        INV["Inventory Service"]
        BIL["Billing Stub"]
        NOT["Notification Service"]
    end

    subgraph EXT["Third-party (separate codebase)"]
        OTT["OTT Platform\nSpring Boot"]
    end

    BROKER[("Kafka\nevent log")]
    WSTORE[("PostgreSQL\norder state + outbox")]
    RSTORE[("MongoDB\nread projections")]

    User -->|"HTTPS REST"| GW
    Ops  -->|"HTTPS REST"| GW

    GW -->|"sync REST"| CAT
    GW -->|"cmd sync / status sync"| ORD
    GW -.->|"OIDC OP endpoints"| OTT

    ORD --> WSTORE
    ORD <-->|"events + commands"| BROKER
    INV <-->|"events + commands"| BROKER
    BIL <-->|"events + commands"| BROKER
    NOT <---|"domain events"| BROKER
    CAT <---|"domain events"| BROKER

    BROKER -->|"projector consumer"| RSTORE
    ORD -->|"Query API: primary"| RSTORE
    ORD -.->|"read-your-writes fallback"| WSTORE
```

---

## 2. Order service — internal architecture

Single deployable, three internal packages. The aggregate is state-stored; order state + domain events are written in one transaction; CDC relays the Tram outbox to Kafka; the saga and projector consume from Kafka; the query API falls back to Postgres on a projection miss.

```mermaid
flowchart TB
    subgraph ORD["Order Service — single deployable"]
        CMD["Command API\nPOST /v1/orders\nPOST /v1/orders/{id}:cancel"]
        IDEM["Idempotency Filter\nidempotency_keys table"]
        AGG["Order\nstate-stored JPA aggregate"]
        SAGA["PlaceOrderSaga\nEventuate Tram Sagas"]
        PG[("PostgreSQL\norders · saga · outbox")]
        CDC["Eventuate CDC\npolls Tram outbox → Kafka"]
        PROJ["OrderProjector\nTram domain-event handler"]
        MONG[("MongoDB\norders_v1\nentitlements_v1\norder_search_v1")]
        QRY["Query API\nGET /v1/orders/*\nGET /v1/entitlements"]
    end

    KAFKA[("Kafka event log")]

    CMD --> IDEM --> AGG
    AGG -->|"order state + domain events\nin one transaction"| PG
    PG --> CDC --> KAFKA
    KAFKA --> SAGA
    SAGA --> KAFKA
    SAGA -->|"invokeLocal: confirm / fail"| AGG
    KAFKA --> PROJ --> MONG
    QRY -->|"primary path"| MONG
    QRY -.->|"read-your-writes fallback\nlookup by orderId"| PG
```

---

## 3. Order placement — end-to-end sequence

The full happy path: synchronous `202` ack, then the asynchronous outbox → CDC → Kafka → saga / projector chain. The note marks the read-your-writes window.

```mermaid
sequenceDiagram
    actor C as Client
    participant API as Command API
    participant DB as PostgreSQL (orders + outbox)
    participant CDC as Eventuate CDC
    participant K as Kafka
    participant SG as PlaceOrderSaga
    participant INV as Inventory / Billing / OTT
    participant PR as OrderProjector
    participant M as MongoDB

    C->>API: POST /v1/orders (Idempotency-Key)
    API->>DB: insert Order (PLACED) + OrderPlaced event — one tx
    API-->>C: 202 Accepted {orderId}
    Note over C,M: GET before projection lands → served from DB (read-your-writes)
    CDC->>DB: poll outbox (published = 0)
    CDC->>K: publish OrderPlaced + saga commands
    K->>SG: drive saga
    SG->>INV: Reserve / Authorize / Provision
    INV-->>SG: replies
    SG->>DB: confirmOrder → status CONFIRMED + OrderConfirmed event (one tx)
    K->>PR: OrderPlaced / OrderConfirmed
    PR->>M: upsert orders_v1 / entitlements_v1
```

---

## 4. PlaceOrderSaga — state machine

Step progression with LIFO compensation. Inventory failure fails without compensation (nothing reserved yet); billing/provisioning failures unwind in reverse order.

```mermaid
stateDiagram-v2
    [*] --> Placed
    Placed --> ReservingInventory : Saga starts
    ReservingInventory --> Reserved : InventoryReserved
    ReservingInventory --> FailingNoCompensation : InventoryReservationFailed
    Reserved --> AuthorizingBilling : next step
    AuthorizingBilling --> Authorized : BillingAuthorized
    AuthorizingBilling --> CompensatingInventory : BillingDeclined
    Authorized --> Provisioning : next step
    Provisioning --> Confirmed : EntitlementProvisioned
    Provisioning --> CompensatingBilling : EntitlementProvisioningFailed (non-retryable)
    CompensatingBilling --> CompensatingInventory : BillingRefunded
    CompensatingInventory --> Failed : InventoryReleased
    FailingNoCompensation --> Failed : terminal
    Confirmed --> [*]
    Failed --> [*]
```

---

## 5. OIDC login flow (subscriber → OTT)

Authorization Code + PKCE. The telco gateway is the OpenID Provider (OP); the OTT platform is the Relying Party (RP).

```mermaid
sequenceDiagram
    participant U as Subscriber
    participant OTT as OTT Platform (RP)
    participant GW as Gateway (OP)

    U->>OTT: open OTT app
    OTT->>U: redirect to /oidc/authorize?client_id=ott&code_challenge=...&scope=openid profile
    U->>GW: GET /oidc/authorize (with PKCE code challenge)
    GW->>U: login screen (subscriber credentials)
    U->>GW: submit credentials
    GW-->>U: redirect to OTT callback with authorization code
    U->>OTT: deliver code
    OTT->>GW: POST /oidc/token (code + code_verifier)
    GW-->>OTT: id_token + access_token
    OTT->>GW: GET /oidc/userinfo (with access_token)
    GW-->>OTT: { sub, msisdn, name }
    OTT->>OTT: lookup entitlement by subscriberId
    OTT-->>U: grant access / stream
```

---

## 6. Entitlement provisioning (Order Saga → OTT)

Machine-to-machine: the saga obtains a client-credentials token, then pushes the entitlement to the OTT admin API (idempotent on `sagaId`). Distinct security context from the subscriber's user token.

```mermaid
sequenceDiagram
    participant SM as Order Saga
    participant OTT as OTT Admin API
    participant TS as Token Server (Gateway)

    Note over SM: Saga step 3 — provision
    SM->>TS: POST /oidc/token (client_credentials, scope=ott:provision)
    TS-->>SM: access_token (short-lived, 5 min)
    SM->>OTT: POST /admin/entitlements (Bearer token) {subscriberId, offerCode, validFrom, validUntil, sagaId}
    OTT->>OTT: upsert entitlement (idempotent on sagaId)
    OTT-->>SM: 201 Created {externalRef}
    Note over SM: append EntitlementProvisioned → OrderConfirmed
```
