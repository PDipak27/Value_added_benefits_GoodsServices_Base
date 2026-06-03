# VA-BAGS — Value Added Benefits (Goods & Services)

A telecom **Value-Added-Services (VAS)** backend that lets a subscriber browse
benefit offers (OTT subscriptions, accessories, priority repair slots) and place
an order that is fulfilled across several services. It is a portfolio-grade
reconstruction of a real telecom VAS platform, built to demonstrate three
patterns working together end-to-end:

- **CQRS** — the Order service separates the write side (event-sourced aggregate)
  from the read side (MongoDB projections served by a dedicated query API).
- **Saga orchestration** — placing an order runs a multi-step distributed
  transaction (reserve inventory → confirm order) with LIFO compensation on
  failure, coordinated by one orchestrator.
- **Event Sourcing** — the Order aggregate's state is derived from an append-only
  event log, not an updatable row.

## Architecture at a glance

```
client ──HTTP──▶ API Gateway ──▶ Order Service ──saga commands──▶ Inventory Service
                                   │  (CQRS + ES)                   Billing Stub
                                   │                                Notification
                                   ▼
                       Postgres (event store, saga, idempotency)
                                   │
                          Eventuate CDC (polling)
                                   │
                                 Kafka ──▶ Order projector ──▶ MongoDB (read model)
```

Writes never touch Kafka directly. Domain events and saga messages are written
transactionally to Postgres outbox tables; **Eventuate CDC** polls those tables
and publishes to **Kafka**, giving an atomic "write + publish" guarantee. The
Order projector consumes from Kafka and materializes the read model in MongoDB.

## Services

| Service | Role |
|---|---|
| **api-gateway** | TLS, JWT validation, routing, OIDC provider endpoints. Owns no domain data. |
| **catalog-service** | Offer definitions, price snapshots, eligibility rules. |
| **order-service** *(depth service)* | Order aggregate (ES), `PlaceOrderSaga` orchestration, MongoDB projections, idempotency, read API. Single deployable, three internal packages (`command` / `saga` / `query`). |
| **inventory-service** | Saga participant. Reserves/commits/releases three inventory types: `PHYSICAL`, `SLOT`, `LICENSE`. |
| **billing-stub-service** | Saga participant. Simulated `Authorize`/`Capture`/`Refund` over a real network boundary. |
| **notification-service** | Pure event-driven consumer (`OrderConfirmed`, `OrderFailed`, …). No inbound commands. |
| **shared-events** | Versioned event/command DTOs shared across services. |

## Tech stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 17 |
| Framework | Spring Boot 3.4.0 |
| Build | Maven (multi-module) |
| Event sourcing | Eventuate Local ES |
| Saga orchestration | Eventuate Tram Sagas |
| Messaging | Apache Kafka (KRaft — no ZooKeeper quorum) |
| Write store | PostgreSQL 18 (event store + saga + idempotency) |
| Read store | MongoDB 7 (projections) |
| Change data capture | Eventuate CDC (Polling mode) |
| Schema registry (dev) | Apicurio (in-memory) |

> ZooKeeper is present **only** for Eventuate CDC leader election, not for Kafka.

## Repository layout

```
vabags_base/
├── pom.xml                 # parent POM (BOMs, Java 17, plugins)
├── docker-compose.yml      # Kafka, ZK, CDC, Mongo, Apicurio
├── shared-events/          # shared event/command contracts
├── api-gateway/
├── catalog-service/
├── order-service/          # CQRS + Saga + ES (the depth service)
├── inventory-service/
├── billing-stub-service/
├── notification-service/
└── deploy/
    ├── postgres-init/      # Eventuate schema SQL
    └── README.md           # full dev setup + troubleshooting
```

## Getting started

Full instructions (Postgres setup, schema, smoke test, troubleshooting) live in
[`deploy/README.md`](deploy/README.md). In short:

```bash
# 1. Create the 'vab' Postgres DB + 'eventuate' user, then apply the schema
psql -U eventuate -d vab -f deploy/postgres-init/01-eventuate-schema.sql

# 2. Start infrastructure (Kafka, ZooKeeper, CDC, MongoDB, Apicurio)
docker-compose up -d

# 3. Build all modules
mvn clean install -DskipTests

# 4. Run the walking skeleton (separate terminals)
cd order-service     && mvn spring-boot:run
cd inventory-service && mvn spring-boot:run
```

Place an order and check its status:

```bash
curl -s -X POST http://localhost:8081/v1/orders \
  -H "Content-Type: application/json" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"subscriberId":"sub_test_001","offerCode":"OTT_NETFLIX_6M",
       "priceSnapshotId":"ps_test_001","amount":599,"currency":"INR",
       "billingMode":"BILL_TO_MOBILE"}'
# → 202 Accepted, {"orderId":"ord_..."}

curl -s http://localhost:8081/v1/orders/<orderId>   # → status: CONFIRMED
```

> **If the read model stays empty / order is stuck at `PLACED`:** CDC is almost
> certainly not running — see the troubleshooting section in `deploy/README.md`.

## Design documents

Detailed design lives outside this module, in `../../Design/`:

| # | Topic |
|---|---|
| 01 | System context, service map, sync/async edges |
| 02 | Per-service bounded contexts |
| 03 | Order service deep dive — CQRS, event catalog, projections, idempotency |
| 04 | Saga state machine, step table, failure modes |
| 05 | API contracts |
| 06 | OTT OIDC auth & provisioning |
| 07 | Infra & stack, docker-compose services, repo layout |
| 08 | Design decisions (ADR-style) |
