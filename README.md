# VA-BAGS — Value Added Benefits (Goods & Services)

A telecom **Value-Added-Services (VAS)** backend that lets a subscriber browse
benefit offers (OTT subscriptions, accessories, priority repair slots) and place
an order that is fulfilled across several services. It is a portfolio-grade
reconstruction of a real telecom VAS platform, built to demonstrate three
patterns working together end-to-end:

- **CQRS** — the Order service separates the write side (state-stored aggregate +
  outbox) from the read side (MongoDB projections served by a dedicated query
  API), with a read-your-writes fallback so reads stay correct during projection lag.
- **Saga orchestration** — placing an order runs a multi-step distributed
  transaction (reserve inventory → confirm order) with LIFO compensation on
  failure, coordinated by one orchestrator.
- **Transactional outbox + event log** — the Order aggregate is stored as ordinary
  state; domain events are written to a Postgres outbox in the same transaction and
  relayed to Kafka, which serves as the durable, replayable event log (analytics,
  recsys). *Note: the design deliberately does **not** event-source the aggregate —
  see [`Design/08-design-decisions.md`](Design/08-design-decisions.md) DD-14.*

> **Scale envelope:** ~50 order TPS, ~300 read RPS — a single-node Postgres
> workload. The patterns above are chosen for *capability*, not throughput.

## Architecture at a glance

```
client ──HTTP──▶ API Gateway ──▶ Order Service ──saga commands──▶ Inventory Service
                                   │  (CQRS + Saga)                 Billing Stub
                                   │                                Notification
                                   ▼
                  Postgres (order state + outbox + saga + idempotency)
                                   │
                          Eventuate CDC (polling)
                                   │
                                 Kafka ──▶ Order projector ──▶ MongoDB (read model)
                              (event log)        │
                                   └─ GET falls back to Postgres on projection miss
```

Writes never touch Kafka directly. Domain events and saga messages are written
transactionally to a Postgres outbox table; **Eventuate CDC** polls it and
publishes to **Kafka**, giving an atomic "write + publish" guarantee. The Order
projector consumes from Kafka and materializes the read model in MongoDB.

> **Migration status:** the design (and these docs) reflect the post-Event-Sourcing
> target. The code is mid-migration from Eventuate Local ES to state-stored +
> Tram outbox; `docker-compose.yml` still provisions the legacy CDC `localpipeline`
> until that lands.

## Services

| Service | Role |
|---|---|
| **api-gateway** | TLS, JWT validation, routing, OIDC provider endpoints. Owns no domain data. |
| **catalog-service** | Offer definitions, price snapshots, eligibility rules. Stored in MongoDB — polymorphic, often-changing offer documents (DD-16). Read/admin API; reads served by a two-tier cache — Caffeine L1 + Redis L2 with a Redis pub/sub L1-invalidation broadcast (evict-on-write + TTL, DD-17/DD-18/DD-19). Fail-open: a Redis outage degrades to Mongo, never an error (DD-20). |
| **order-service** *(depth service)* | State-stored Order aggregate + outbox, `PlaceOrderSaga` orchestration, MongoDB projections, idempotency, read API. Single deployable, three internal packages (`command` / `saga` / `query`). |
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
| Order write model | State-stored JPA aggregate + Eventuate Tram transactional outbox |
| Saga orchestration | Eventuate Tram Sagas |
| Messaging / event log | Apache Kafka (KRaft — no ZooKeeper quorum) |
| Write store | PostgreSQL 18 (order write side: order state + outbox + saga + idempotency) |
| Document store | MongoDB 7 (order read-model projections + catalog store; order reads have a read-your-writes fallback) |
| Catalog read-cache | Caffeine L1 (in-process) + Redis 7 L2 (shared); evict-on-write + Redis pub/sub L1 broadcast (skip-self) + 15s TTL backstop, not event-driven; fail-open on Redis outage — DD-17/DD-18/DD-19/DD-20 |
| Change data capture | Eventuate CDC (Polling mode) |
| Schema registry (dev) | Apicurio (in-memory) |

> ZooKeeper is present **only** for Eventuate CDC leader election, not for Kafka.

## Repository layout

```
vabags_base/
├── pom.xml                 # parent POM (BOMs, Java 17, plugins)
├── docker-compose.yml      # Kafka, ZK, CDC, Mongo, Redis, Apicurio
├── shared-events/          # shared event/command contracts
├── api-gateway/
├── catalog-service/
├── order-service/          # CQRS + Saga + outbox (the depth service)
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

# 2. Start infrastructure (Kafka, ZooKeeper, CDC, MongoDB, Redis, Apicurio)
docker-compose up -d

# 3. Build all modules
mvn clean install -DskipTests

# 4. Run the services (separate terminals — see deploy/README.md for the full table)
cd order-service         && mvn spring-boot:run   # :8081  command + saga + query
cd inventory-service     && mvn spring-boot:run   # :8082  saga: reserve/commit/release
cd billing-stub-service  && mvn spring-boot:run   # :8083  saga: authorize/capture/refund
cd notification-service  && mvn spring-boot:run   # :8084  reacts to OrderConfirmed/Failed
cd catalog-service       && mvn spring-boot:run   # :8085  offer browse + eligibility
cd api-gateway           && mvn spring-boot:run   # :8080  routes to catalog + order
```

> **Minimal happy-path saga** needs only order + inventory + billing (8081–8083).
> The gateway (8080) is the single front door: `/v1/offers/**` → catalog,
> `/v1/orders/**` and `/v1/entitlements/**` → order-service. JWT validation and
> the OIDC provider endpoints are deferred to a later iteration.

Place an order and check its status:

```bash
curl -s -X POST http://localhost:8081/v1/orders -H "Content-Type: application/json" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"subscriberId":"sub_test_001","offerCode":"OTT_NETFLIX_6M",
       "priceSnapshotId":"ps_test_001","amount":599,"currency":"INR",
       "billingMode":"BILL_TO_MOBILE"}'
# → 202 Accepted, {"orderId":"ord_..."}

curl -s http://localhost:8081/v1/orders/<orderId>   # → status: CONFIRMED
```

> **If the read model stays empty / order is stuck at `PLACED`:** CDC is almost
> certainly not running — see the troubleshooting section in `deploy/README.md`.

## Design documents

Full design lives in [`Design/`](Design/):

| # | File | Topic |
|---|------|-------|
| 01 | [01-system-context.md](Design/01-system-context.md) | System context, service map, sync/async edges |
| 02 | [02-service-responsibilities.md](Design/02-service-responsibilities.md) | Per-service bounded contexts |
| 03 | [03-order-service-deep-dive.md](Design/03-order-service-deep-dive.md) | Order service deep dive — CQRS, event catalog, projections, idempotency |
| 04 | [04-saga-design.md](Design/04-saga-design.md) | Saga state machine, step table, failure modes |
| 05 | [05-api-contracts.md](Design/05-api-contracts.md) | API contracts |
| 06 | [06-ott-auth.md](Design/06-ott-auth.md) | OTT OIDC auth & provisioning |
| 07 | [07-infra-and-stack.md](Design/07-infra-and-stack.md) | Infra & stack, docker-compose services, repo layout |
| 08 | [08-design-decisions.md](Design/08-design-decisions.md) | Design decisions (ADR-style) |
| — | [diagrams.mmd](Design/diagrams.mmd) | All Mermaid diagrams (paste into mermaid.live) |
