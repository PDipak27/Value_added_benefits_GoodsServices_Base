# Infrastructure & Stack

## Technology choices

| Concern | Choice | Reason |
|---|---|---|
| Language / runtime | Java 17, Spring Boot 3.4.0 | Eventuate BOM targets 3.4.0; JDK 17 used (no JDK 21 installed locally) |
| Build | **Maven** (multi-module) | User preference |
| Order write model | **State-stored JPA aggregate + Eventuate Tram outbox** (`DomainEventPublisher`) | Order is persisted as a normal row; domain events are written to the Tram `message` outbox in the *same* transaction and relayed to Kafka. The aggregate is **not** event-sourced — see DD-14. |
| Saga | **Eventuate Tram Sagas** (`eventuate-tram-sagas-spring-orchestration-simple-dsl-starter`) | Orchestration DSL; layered on Tram messaging |
| Outbox → broker | **Eventuate CDC in Polling mode** (`SPRING_PROFILES_ACTIVE=EventuatePolling,Kafka`) — a single `trampipeline` reader polling the Tram `eventuate.message` outbox. | One CDC service relays both the Order domain events and the Inventory/Billing saga command/reply traffic (all flow through the Tram outbox). With ES removed, the former `localpipeline` (Local ES `eventuate.events`) is retired — fewer moving parts. No wal2json, no logical replication slot — simpler dev on Windows + Postgres 18. Trade-off: slightly higher publish latency vs WAL tailing — irrelevant at ~50 TPS. |
| Broker | **Apache Kafka** via `apache/kafka:3.7.1` in **KRaft mode** | Kafka itself needs no ZooKeeper (KRaft handles broker/controller quorum). Retained as the durable **event log** (replay, analytics, recsys) — a strategic asset, not a scaling device. |
| CDC leader election | **ZooKeeper** (`confluentinc/cp-zookeeper:7.7.1`) on `:2181` | Eventuate CDC uses a ZK lock (`/eventuate/cdc/leader/tram`) to coordinate CDC replicas — required by the framework even with a single instance, and what makes a **multi-instance (HA) CDC** deployment safe. Independent of Kafka's KRaft quorum. |
| Write store | **PostgreSQL 18** (locally installed, not containerised) — Order write side only (order state + Tram outbox + saga + idempotency) | User preference; CDC connects via `host.docker.internal` |
| Read / document store | **MongoDB 7** — Order read-model projections **and** the Catalog store (DD-16) | Document model suits denormalized order projections; also fits polymorphic, often-changing offer documents (Catalog is its own `vab_catalog` database) |
| Catalog read-cache | **Caffeine L1 (in-process) + Redis 7 L2 (shared)** — two-tier (DD-17 / DD-18 / DD-19) | Read-heavy, write-rare catalog; L1 avoids re-deserializing ~5000 offers per browse, L2 backstops L1 misses. Invalidation is local evict-on-write + a Redis pub/sub broadcast that clears peer L1s (skip-self, DD-19); 15s TTL on both tiers is the backstop (not event-driven — writer and cache are the same service) |
| Schema registry | **Apicurio** (OSS, in-memory for dev) | Same REST API as Confluent SR; zero license cost |
| OIDC Provider | Spring Authorization Server (iteration 6+) | Production-grade; not hand-rolled |
| Observability | OTel → Loki + Grafana | Existing repo wiring |
| Tracing | W3C `traceparent` in HTTP headers + Kafka message headers | Traces cross sync + async hops |

---

## Platform BOM versions (confirmed on Maven Central)

| Artifact | Version |
|---|---|
| `io.eventuate.platform:eventuate-platform-dependencies` | `2026.0.RELEASE` |
| `org.springframework.boot:spring-boot-dependencies` | `3.4.0` |
| `io.eventuate.tram.core:eventuate-tram-spring-jdbc-kafka` | managed by BOM (`0.36.0.RELEASE`) |
| `io.eventuate.tram.core:eventuate-tram-spring-events` | managed by BOM |
| `io.eventuate.tram.sagas:eventuate-tram-sagas-spring-orchestration-simple-dsl-starter` | managed by BOM (`0.25.0.RELEASE`) |
| `io.eventuate.tram.sagas:eventuate-tram-sagas-spring-participant` | managed by BOM |
| `eventuateio/eventuate-cdc-service` (Docker) | `0.19.0.RELEASE` |

**Redesign (DD-14):** the two `io.eventuate.local.java:eventuate-local-java-spring-{jdbc,events}-starter` artifacts are **dropped** — the write side no longer event-sources. Domain events are published/consumed via `eventuate-tram-spring-events` (`DomainEventPublisher` on the write side, `DomainEventHandlers` in the projector).

**Key rule:** never declare Eventuate artifact versions explicitly. Always let the BOM manage them. Mixing versions manually is the #1 source of classpath conflicts.

---

## Why Eventuate Tram only (no Local ES)

Eventuate has two distinct layers:
- **Tram** — messaging, transactional outbox, saga participant/orchestrator, and `DomainEventPublisher` for publishing domain events atomically with a JDBC write. No event sourcing.
- **Eventuate Local ES** — full event-sourced aggregates (`ReflectiveMutableCommandProcessingAggregate`). Sits on top of Tram.

**All services now use Tram only.** The Order aggregate is state-stored (a JPA entity) and emits domain events through the Tram outbox; Inventory and Billing are Tram saga participants. This keeps "events on Kafka" (the strategic asset) without paying the event-sourcing tax — see DD-14 for the reasoning. The decisive insight: *publishing domain events* and *event-sourcing the aggregate* are independent choices, and at ~50 TPS only the former earns its keep.

---

## Repository layout

```
VA-BAGS/
├── Design/                           (architecture documents)
└── source/
    └── vabags_base/                  (Maven root; cd here for all build/run commands)
        ├── pom.xml                   (parent BOM + Maven multi-module)
        ├── docker-compose.yml        (Kafka KRaft + CDC + MongoDB + Apicurio)
        │
        ├── shared-events/            (versioned event POJOs + Saga commands/replies)
        │
        ├── api-gateway/              (Spring Cloud Gateway; Spring Auth Server in iter 6)
        ├── catalog-service/
        │
        ├── order-service/            (single deployable — command + saga + query)
        │   └── src/main/java/com/vab/order/
        │       ├── command/api/      OrderCommandController
        │       ├── command/domain/   Order (state-stored JPA entity), commands
        │       ├── command/service/  OrderCommandService (+ DomainEventPublisher)
        │       ├── saga/             PlaceOrderSaga, PlaceOrderSagaData
        │       ├── query/api/        OrderQueryController (Mongo + read-your-writes fallback)
        │       ├── query/document/   OrderView (MongoDB document)
        │       ├── query/projection/ OrderProjector (Tram domain-event handler)
        │       ├── query/repository/ OrderViewRepository
        │       └── idempotency/      IdempotencyKey, IdempotencyKeyRepository
        │
        ├── inventory-service/
        │   └── src/main/java/com/vab/inventory/
        │       ├── command/          InventoryCommandHandlers (@SagaCommandHandlers)
        │       └── domain/           LicensePool, LicensePoolRepository
        │
        ├── billing-stub-service/
        ├── notification-service/
        │
        └── deploy/
            ├── postgres-init/
            │   ├── 01-eventuate-schema.sql   (events/entities/message/offset tables)
            │   └── 02-wal-and-slot.sql       (kept for reference; not used in Polling mode)
            └── README.md                     (setup instructions + troubleshooting)
```

---

## docker-compose services

| Service | Image | Host Port | Purpose |
|---|---|---|---|
| `vab-kafka` | `apache/kafka:3.7.1` | 9092 | Broker (KRaft — no ZK quorum) |
| `vab-zk` | `confluentinc/cp-zookeeper:7.7.1` | 2181 | CDC leader-election lock store (not used by Kafka) |
| `vab-cdc` | `eventuateio/eventuate-cdc-service:0.19.0.RELEASE` | 8080 | Polling reader → Kafka publisher (two pipelines: Local ES + Tram) |
| `vab-mongo` | `mongo:7` | 27017 | Read projections + catalog store |
| `vab-redis` | `redis:7-alpine` | 6379 | Catalog read-cache (DD-17) |
| `vab-apicurio` | `apicurio/apicurio-registry-mem:latest-release` | 8090 | Schema registry (container :8080 mapped to host :8090 to avoid collision with CDC) |

Postgres runs locally — CDC connects via `host.docker.internal:5432`.

`depends_on` chain: CDC waits for both `kafka` (publish target) and `zookeeper` (leader lock) to be healthy before starting. All containers carry healthchecks; CDC's check hits `localhost:8080/actuator/health` (Spring Boot Actuator).

> **Operational gotcha (read-side silently empty).** Because CDC is gated on `service_healthy`, if Kafka/ZK miss their healthcheck window on a cold start, Compose leaves CDC in `Created` and never starts it. The write side still works (aggregate + saga rows are JDBC writes), so it *looks* healthy — but nothing is published to Kafka, so the projector never fires and the Mongo read model stays empty (no `vab` DB). Healthcheck `timeout`/`start_period`/`retries` are sized generously to avoid this, and CDC runs `-Xmx512m` (a starved heap makes its boot exceed the health window). If you ever see `vab-cdc` in `Created`/down with an empty read model, `docker start vab-cdc` flushes the `published=0` backlog from `eventuate.events`.

---

## Gradle → Maven migration note

The Eventuate reference examples use Gradle. The Maven translation is:
- `implementation(platform(...))` → `<dependencyManagement>` + BOM import
- `runtimeOnly` → `<scope>runtime</scope>`
- `-parameters` compiler flag → `<compilerArgs><arg>-parameters</arg></compilerArgs>` in maven-compiler-plugin

The `-parameters` flag is **required** by Eventuate Tram Sagas for method parameter name reflection.
Omitting it causes `IllegalArgumentException: No message handler found` at runtime.

---

## Cross-cutting production practices

| Practice | Implementation |
|---|---|
| Atomic write + publish | Eventuate CDC Polling mode — app never writes to Kafka directly |
| Idempotency | `(subscriberId, idempotencyKey)` in `orders.idempotency_keys`; `(sagaId, stepId)` via Tram `received_messages` |
| Distributed tracing | W3C `traceparent` propagated in HTTP headers + Kafka message headers |
| Event versioning | Additive-only `.v1` classes in `shared-events`; breaking change = new class + dual-publish window |
| Poison messages | Tram DLQ per consumer group; projector skips stale via `version` check |
| Graceful shutdown | `server.shutdown: graceful` in all service `application.yml` |
| Optimistic locking | `@Version` on `LicensePool` and `Order`; the `version` rides on emitted events so the projector rejects stale updates |
