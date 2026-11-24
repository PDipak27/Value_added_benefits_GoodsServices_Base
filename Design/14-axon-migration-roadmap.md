# Eventuate Tram → Axon Framework — Impact Assessment & Migration Roadmap

Status: **proposal** (nothing implemented). Scope: replace Eventuate Tram (messaging, outbox/CDC,
domain events, orchestration sagas) with Axon Framework 4.x across VA-BAGS, including the
unit / integration / e2e test suites.

Related: [04-saga-design.md](04-saga-design.md), [08-design-decisions.md](08-design-decisions.md)
(DD-02, DD-05, DD-11, DD-12, DD-14, DD-25, DD-26, DD-27), [12-roadmap.md](12-roadmap.md),
[13-failure-handling-strategy.md](13-failure-handling-strategy.md).

---

## 1. Verdict — how big is it?

**Large, but contained.** It is a re-platforming of the messaging backbone, not a domain rewrite.

| Dimension | Size | Why |
|---|---|---|
| Services touched | **5 of 8** + 2 shared libs | order, inventory, billing, fulfilment, notification, `shared-events`, `shared-observability`. **catalog, api-gateway, ott-service: no code change.** |
| Main source files with Eventuate imports | **~37** | 10 in order-service, 2–3 per participant, 2 in notification, 25 in shared-events (marker interfaces only), 3 in shared-observability |
| Hardest single piece | `PlaceOrderSaga` (555 lines) | Eventuate's step DSL (predicates, `withCompensation`, `withFailure` → auto-rollback) has **no Axon equivalent**. Axon sagas are event-driven state machines; every branch and every LIFO compensation must be written by hand. |
| Contract change | **Every command and reply** in `shared-events` | Replies today are correlated by Tram's `sagaId` header. In Axon they become events the saga matches by an association property → each command/reply must carry `orderId`. |
| Infra | **Removes** Kafka, ZooKeeper, CDC, 2–3 init SQL scripts; **adds** Axon Server | compose (local, EC2, k8s-infra), `k8s/`, `k8s-eks/base` (02, 03), Jenkins e2e stack bring-up |
| Tests | **24 test files** reference Eventuate (~4,500 lines); ~9,000 test lines total | Unit: 2 heavy rewrites + ~10 mechanical. IT: 4 base classes + 1 heavy rewrite. e2e: test code ~unchanged, harness changes. |
| Design docs | 7 docs | 04, 07, 08 (new DD), 11, 12, 13, diagrams |

**Effort (one developer who knows the codebase, new to Axon):** ≈ **18–26 working days**,
of which ~40% is saga + its tests. See §8 for the per-phase breakdown.

**What gets better:** no CDC/ZooKeeper/outbox-id collisions (`OutboxIdNodeReporter` and
`EVENTUATE_MAC_ADDRESS` disappear), step **timeouts via deadlines** (Design/04 lists them; Tram
never had them), built-in **dead-letter queue** (closes the DLQ backlog item), **projection
replay**, native correlation/causation metadata, and `SagaTestFixture` / `AggregateTestFixture`
for given-when-then tests that still satisfy DD-25 (no Spring, no containers).

**What gets worse / must be re-earned:**
1. **The "state change + publish in one DB transaction" guarantee** (DD-02/DD-05/DD-14) is not
   free with Axon Server — see decision D2.
2. **Command de-duplication** — Tram's `received_messages` table goes away; participants need
   an explicit idempotency key (Design/04 already specifies `(sagaId, stepId)` →
   `processed_messages`; now it must actually be implemented).
3. **Compensation is no longer framework-driven** — LIFO rollback and "forward-only after pivot"
   become saga code that must be tested exhaustively (that is what the new saga fixture tests are for).

---

## 2. Current Eventuate footprint (inventory)

| Module | Eventuate usage | Files |
|---|---|---|
| root `pom.xml` | `eventuate-platform-dependencies` BOM, `eventuate-tram-spring-micrometer-tracing-bom` | 1 |
| shared-events | `implements Command` (13), `implements DomainEvent` (8), `EventuateJackson` (JSonMapper + JavaTimeModule hook) | 25 |
| shared-observability | `CorrelationMessageInterceptor` (Tram `MessageInterceptor`), `OutboxIdNodeReporter` (Tram `IdGenerator`), auto-config | 3 |
| order-service | `PlaceOrderSaga` (SimpleSaga DSL), `PlaceOrderSagaData`, `OrderCommandService` (`DomainEventPublisher` ×11, `SagaInstanceFactory`), `FulfilmentReDrive` + `EntitlementRevoke` (raw `CommandProducer` + `MessageConsumer` on a private reply channel), `OrderProjector` + `OrderSearchProjector` (`DomainEventHandlers`), `DomainEventsConfig` (two dispatchers), app main | 10 |
| inventory / billing / fulfilment | `*CommandHandlers` via `SagaCommandHandlersBuilder`, replies `withSuccess`/`withFailure`, app config; inventory also pulls `eventuate-tram-sagas-spring-flyway` | 2 each |
| notification-service | `NotificationEventConsumer` (`DomainEventHandlers`), `EventConsumerConfig` (Kafka consumer + dispatcher) | 2 |
| config | `eventuate:` / `eventuatelocal:` blocks in 6 `application.yml`; DB user `eventuate` everywhere | 6 |
| infra | `eventuate-cdc` + `zookeeper` + Kafka in `docker-compose*.yml`; `k8s-eks/base/02-kafka.yaml`, `03-zookeeper-cdc.yaml`; `deploy/postgres-init/01-eventuate-schema.sql`, `02-wal-and-slot.sql`, `04-tram-saga-schema.sql`; `Design/createKafkaTopics.ps1` | ~10 |

---

## 3. Key decisions (make these first — they change the size of everything else)

### D1 — Transport: Axon Server vs. "Axon without Axon Server"

| | **A. Axon Server CE** *(recommended)* | B. JDBC event store per service + Kafka extension + distributed command bus |
|---|---|---|
| Command routing | Axon Server (gRPC), point-to-point, consistent routing by `@RoutingKey` | `axon-springcloud` / JGroups extension + service discovery (none exists in VA-BAGS today) |
| Event distribution | Axon Server | `axon-kafka` extension, publisher in TRACKING mode (acts as outbox relay) |
| Kafka / ZK / CDC | **All removed** | Kafka stays; ZK + CDC removed |
| Moving parts | 1 new container | 2 extensions + discovery + topic management |
| Caveats | CE is single-node (no clustering — that is Enterprise); a new stateful component to run on k8s (StatefulSet + PVC) | Much more custom glue; few reference examples |

Recommend **A**. It is the idiomatic Axon setup and the cleanest interview story
("replaced outbox + CDC + broker with an event store/router").

### D2 — Order aggregate: stay state-stored (DD-14) or event-source it?

Axon supports both (`GenericJpaRepository` for state-stored, `EventSourcingRepository` for ES).

* **State-stored + Axon Server**: JPA commit and Axon Server event append are two resources —
  the Tram single-transaction outbox guarantee is lost (narrow window, but real).
* **Event-sourced** *(recommended)*: the event stream *is* the state; no dual write for the
  Order. Original iteration-1–3 design was ES, so this realigns code with that intent.
  The `orders.orders` table becomes redundant on the write side (keep it as a projection only if
  something reads it — today the query side is Mongo, plus the read-your-writes fallback of DD-15,
  which must be re-pointed at the aggregate/event store or a Postgres projection).

Participants (inventory/billing/fulfilment) stay **state-stored JPA** (inventory's row-level
concurrency, `InventoryConcurrencyIT`, depends on it). Their "DB write + publish reply event"
is the same dual write → mitigate with **idempotent handlers + saga deadlines that re-send**
(D4). Event-sourcing participants is an optional later phase, not part of this migration.

> New ADR: **DD-33 — Axon Framework replaces Eventuate Tram** (supersedes DD-05, DD-11, DD-12;
> revises DD-02, DD-14; DD-26/DD-27 semantics unchanged).

### D3 — Reply style: command results vs. reply events

Axon offers `commandGateway.send(cmd).whenComplete(...)` (result returned to caller), but that
callback **is not persisted** — a crash between send and reply loses it.
**Saga path: participants publish reply events** (`InventoryReserved`, `BillingDeclined`, …) and
the saga handles them with `@SagaEventHandler(associationProperty = "orderId")`.
**Admin paths (re-drive, revoke): command results are fine** — the admin can simply retry.

### D4 — Timeouts & idempotency

* Each saga step schedules a deadline (`DeadlineManager`) — use a **persistent** one
  (`DbSchedulerDeadlineManager` or JobRunr), not `SimpleDeadlineManager`. On deadline: re-send
  the same command (network retry — same `stepId`) up to N times, then compensate (pre-pivot)
  or park (post-pivot). This implements the "Retry semantics" table in Design/04 for real.
* Every participant persists `(orderId, step)` in a `processed_commands` table (or unique
  constraint on the business row) and replays the stored reply on duplicates.

### D5 — Versions & serializer

Axon Framework **4.x latest** (Spring Boot 3.4 / Java 17 compatible) via `axon-bom`;
`axon-spring-boot-starter`, `axon-micrometer`, `axon-tracing-opentelemetry`.
Axon Framework 5 is a rewritten API — don't target it in this migration.
Serializer: **Jackson for general/events/messages** (`axon.serializer.*=jackson`), with the Spring
`ObjectMapper` (already has `JavaTimeModule`) → `EventuateJackson` is deleted. Avoid XStream.

---

## 4. Concept mapping (Eventuate → Axon)

| Eventuate Tram | Axon |
|---|---|
| `DomainEventPublisher.publish(aggregateType, id, events)` | `AggregateLifecycle.apply(event)` inside `@CommandHandler` on the `@Aggregate` |
| `implements DomainEvent` / `implements Command` | plain POJOs (records ok); `@TargetAggregateIdentifier` on Order commands, `@RoutingKey` on participant commands; `@Revision` for versioning (DD-13) |
| `SimpleSaga` + `step().invokeParticipant(...).onReply(...).withCompensation(...)` | `@Saga` class: `@StartSaga`, `@SagaEventHandler`, `@EndSaga`, `SagaLifecycle.associateWith`, explicit next-step / compensation methods |
| `PlaceOrderSagaData` (saga_data_json VARCHAR) | saga fields serialized by the `SagaStore` (`JpaSagaStore` → `saga_entry`, `association_value_entry`) — the 256-char `cap()` workaround can go |
| `withFailure(...)` → automatic LIFO compensation | failure **event**; saga handler calls `compensate()` which walks a recorded stack of taken holds |
| `withSuccess(...)` | success event |
| `invokeLocal(...)` | saga sends a command to the Order aggregate (`ConfirmOrderCommand`, `CompleteOrderCommand`, …) |
| `SagaRollback` exception from a local step | explicit `compensate("USER_CANCEL")` call |
| Cooperative cancel flag polled at checkpoints | Order aggregate applies `OrderCancelRequested`; saga handles it and sets a field — checkpoints read saga state, no DB poll |
| `SagaCommandHandlersBuilder.fromChannel("billingService")` | `@CommandHandler` methods on a Spring `@Component` (external command handler) |
| `CommandProducer.send(channel, cmd, replyChannel)` + `MessageConsumer.subscribe(...)` (re-drive, revoke) | `commandGateway.send(cmd)` returning `CompletableFuture<Reply>` |
| `DomainEventHandlersBuilder.forAggregateType(...).onEvent(...)` | `@EventHandler` in a `@ProcessingGroup` (pooled streaming processor) |
| Projector discarding stale events by `version` | `@SequenceNumber long seq` parameter on the handler |
| `received_messages` dedupe | tracking tokens (`token_entry`) for event processors; explicit idempotency for commands (D4) |
| `MessageInterceptor` (correlation id) | `MessageDispatchInterceptor` + `MessageHandlerInterceptor`, or simply Axon's built-in `MessageOriginProvider` (correlationId + traceId) via `CorrelationDataProvider` |
| `eventuate-tram-spring-micrometer-tracing` | `axon-tracing-opentelemetry` + `axon-micrometer` |
| Eventuate CDC + Kafka + ZooKeeper | Axon Server |
| none | `DeadlineManager` (step timeouts), sequenced **dead-letter queue** (`JpaSequencedDeadLetterQueue`), processor **replay** (`@ResetHandler`) |

---

## 5. Target saga design (PlaceOrderSaga on Axon)

The 15-step table in Design/04 stays the **specification**; only the mechanics change.

```text
@StartSaga  OrderPlaced            → associate orderId; PAY_NOW ? send Reserve : send CheckLimit
InventoryReserved                  → push(RELEASE); send Authorize
InventoryReservationFailed         → fail("RESERVE_INVENTORY")          (stack empty)
AccountLimitOk                     → send Allocate
AccountLimitExceeded               → fail("CHECK_ACCOUNT_LIMIT")
InventoryAllocated                 → push(RELEASE); prePivotCheckpoint()
BillingAuthorized                  → send Commit
BillingDeclined                    → compensate(); fail("AUTHORIZE_BILLING")
InventoryCommitted                 → prePivotCheckpoint()
InventoryCommitFailed              → compensate(); fail("COMMIT_INVENTORY")
prePivotCheckpoint()               → cancelRequested ? compensate(); cancel()  : send Capture | AppendLedger
BillingCaptured / LedgerAppended   → PIVOT passed (stack cleared — forward-only); send ConfirmOrder
BillingCaptureFailed               → compensate(); fail("CAPTURE_BILLING")
OrderConfirmed                     → cancelRequested ? forwardRecover() : send Fulfil
OrderFulfilled                     → send CompleteOrder → @EndSaga on OrderCompleted
OrderFulfilmentFailed              → forwardRecover()
OrderProvisioningFailed            → send MarkFulfilmentFailed (DD-27 park) → @EndSaga
forwardRecover()                   → send Refund | ReverseLedger → on reply send Release → send CancelRefunded → @EndSaga
OrderCancelRequested (any time)    → cancelRequested = true
@DeadlineHandler step-timeout      → re-send (same stepId) ≤ N, then compensate (pre-pivot) / park (post-pivot)
```

Rules that used to be enforced by Eventuate and are now enforced by **code + tests**:
* nothing is pushed onto the compensation stack after the pivot;
* post-pivot participants never publish a failure event that triggers `compensate()`;
* every terminal path ends the saga exactly once (`@EndSaga` or `SagaLifecycle.end()`).

---

## 6. Phased roadmap

Each phase ends green (build + its test layer) so the branch is always shippable.
Work on a long-lived branch `feat/axon-migration`; Eventuate `main` remains the baseline.

### Phase 0 — Baseline & spike (1–2 d)
* Run the **full** suite on current `main` (`mvn verify -Pit`, `-Pe2e`) and archive the reports —
  the e2e results are the behavioural oracle for the whole migration.
* Spike: one participant (billing `authorize`) + a 2-step toy saga against Axon Server in compose.
  Confirm Boot 3.4 + Axon versions, Jackson serializer with `Instant`, tracing propagation into Tempo.
* Write **DD-33** (D1–D5 above).

### Phase 1 — Platform & build (1–2 d)
* root `pom.xml`: drop both Eventuate BOMs, add `axon-bom`; remove all `io.eventuate*` deps in
  6 module poms; add `axon-spring-boot-starter` (+ `axon-micrometer`, `axon-tracing-opentelemetry`).
* `docker-compose*.yml`: remove `eventuate-cdc`, `zookeeper`, `kafka`; add `axoniq/axonserver`
  (8024 UI/HTTP, 8124 gRPC, volume for event data, dev-mode on). Same for `docker-compose.k8s-infra.yml`, `.ec2.yml`.
* `deploy/postgres-init`: delete `01-eventuate-schema.sql`, `02-wal-and-slot.sql`, `04-tram-saga-schema.sql`.
* Flyway (per service DB schema): `token_entry`, `saga_entry` + `association_value_entry` (order only),
  `dead_letter_entry`, deadline-manager table, `processed_commands` (participants).
* `application.yml` ×6: replace `eventuate`/`eventuatelocal` blocks with `axon.axonserver.servers`,
  serializer, processor config (pooled streaming, segments, DLQ).

### Phase 2 — shared-events & shared-observability (2 d)
* Strip `implements Command` / `implements DomainEvent`; add `orderId` (+ `stepId`) to **every**
  command and reply that lacks it (e.g. `ReserveInventoryCommand(offerCode, qty)` today);
  `@RoutingKey` on participant commands; new Order commands (`PlaceOrder`, `ConfirmOrder`,
  `CompleteOrder`, `RequestCancel`, `Cancel`, `CancelRefunded`, `Fail`, `MarkFulfilmentFailed`,
  `CompleteFromReDrive`, `ParkFromReDrive`, `RevokeEntitlement`) and `OrderCancelRequested` event.
* Delete `EventuateJackson` and its static-init calls in event classes.
* Observability: rewrite `CorrelationMessageInterceptor` as Axon dispatch + handler interceptors
  (or adopt `MessageOriginProvider` and map `correlationId`/`traceId` into MDC); delete
  `OutboxIdNodeReporter`; update `ObservabilityAutoConfiguration`.

### Phase 3 — Participants (3–4 d) — lowest risk, do first
inventory, billing, fulfilment, in that order:
* `SagaCommandHandlersBuilder` → `@CommandHandler` methods; `withSuccess(X)` / `withFailure(Y)` →
  `eventGateway.publish(X | Y)` (or `apply` if later made aggregates).
* Idempotency on `(orderId, stepId)` with stored-reply replay (D4).
* Fulfilment: `fulfil` handler both **publishes** the reply event (saga path) and **returns** it
  (re-drive/revoke path via command result) — or two command types; pick one and document.
* Inventory: keep pessimistic/optimistic locking exactly as is.

### Phase 4 — Order write side (3–4 d)
* `Order` → `@Aggregate` (event-sourced per D2): `@CommandHandler` + `@EventSourcingHandler`;
  state transitions keep their current invariants (`requestCancel` refuses terminal states, etc.).
* `OrderCommandService` shrinks to: idempotency-key handling + catalog price verify (DD-32) +
  `commandGateway.sendAndWait(PlaceOrderCommand)`. The 11 `domainEventPublisher.publish(...)`
  calls move into the aggregate.
* `FulfilmentReDrive` / `EntitlementRevoke`: drop the private reply channel and `MessageConsumer`
  subscription (and the `ApplicationReadyEvent` workaround) → `commandGateway.send(...)` + result handling.
* Re-check DD-15 read-your-writes fallback (previously read `orders.orders`).

### Phase 5 — PlaceOrderSaga (4–6 d) — highest risk
* Rewrite per §5; deadlines per step; compensation stack.
* Delete `PlaceOrderSagaData` (fields move into the saga) and the `cap()` workaround.
* Build the **SagaTestFixture scenario matrix** (§7.1) *before* wiring to the real participants.

### Phase 6 — Read side & notification (2 d)
* `OrderProjector`, `OrderSearchProjector` → `@EventHandler` in processing groups
  `order-view`, `order-search`; stale-event guard via `@SequenceNumber`; add `@ResetHandler`
  (drop Mongo collection) to enable replay.
* `NotificationEventConsumer` → `@EventHandler`, processing group `notification`, DLQ enabled;
  delete `EventConsumerConfig`; replace `received_messages` dedupe with a unique key on
  `@MessageIdentifier` in the delivery table (new Flyway migration).

### Phase 7 — Deployment (2–3 d)
* `k8s/`, `k8s-eks/base`: delete `02-kafka.yaml`, `03-zookeeper-cdc.yaml`; add Axon Server
  StatefulSet + PVC + Service; update `00-config` / service env; kustomization.
* Jenkins (`jenkins-ec2-local.ci`, `Jenkinsfile.eks`), `buildAppImages.ps1`, `importImagesToK3d.ps1`,
  `Design/createKafkaTopics.ps1` (delete), Grafana dashboards / k6 script if they reference
  Kafka or CDC metrics; Loki/OTel labels unchanged.

### Phase 8 — Hardening & docs (1–2 d)
* Failure drills from Design/13: kill a participant mid-saga (deadline path), restart Axon Server,
  poison event → DLQ, replay a projection.
* Update Design 04, 07, 08 (DD-33), 11, 12, 13, diagrams; DB user rename `eventuate` → `vab` (optional, cosmetic but touches every config + every IT base).

---

## 7. Test migration — all three layers

Current suite: **~9,000 test lines**; **24 files** reference Eventuate.
Rule (DD-25) still holds: unit tests = pure JUnit 5 + Mockito + AssertJ. Axon's
`AggregateTestFixture` and `SagaTestFixture` (module `axon-test`) are plain-JVM fixtures — no Spring
context, no containers — so they **comply** with DD-25 and become the main tools.

### 7.1 Unit tests

| Test | Today | Change | Size |
|---|---|---|---|
| `PlaceOrderSagaTest` (40 tests, 524 lines) | calls step methods directly with hand-built `PlaceOrderSagaData` | **Rewrite** on `SagaTestFixture`: `givenAggregate(orderId).published(...)` → `whenPublishingA(reply)` → `expectDispatchedCommands(...)`, `expectScheduledDeadline...`, `expectActiveSaga/expectNoActiveSaga`. Same scenario list + new: per-step **timeout → retry → compensate**, **cancel event arriving at each phase**, "no compensation after pivot", saga ends exactly once. Expect ~55–65 tests. | **L** |
| `OrderTest` (11) | JPA entity state transitions | Move to `AggregateTestFixture<Order>`: `given(events).when(command).expectEvents(...)` / `expectException(...)` | M |
| `OrderCommandServiceTest` (32, 511 lines) | mocks `DomainEventPublisher`, `SagaInstanceFactory` | Split: ~⅔ of cases (state transitions + published events) move to the aggregate fixture; remainder (idempotency key, catalog price verify DD-32, error mapping) stays with a mocked `CommandGateway` | **L** |
| `FulfilmentReDriveTest`, `EntitlementRevokeTest` (4 + 4) | mock `CommandProducer`/`MessageConsumer`, raw reply `Message`s | Mock `CommandGateway` returning `CompletableFuture.completedFuture(OrderFulfilled / OrderProvisioningFailed)`; assert follow-up command | S |
| `BillingCommandHandlersTest` (12), `InventoryCommandHandlersTest` (13), `FulfilmentCommandHandlersTest` (15) | build `CommandMessage` mocks, assert reply `Message` headers via `Replies` | Call handler with the plain command; assert published event via mocked `EventGateway` (captor). **Rewrite only the `Replies` helper** (keep `assertSuccess/assertFailure` names → now "published event of type X") so test bodies change minimally. Add: duplicate `(orderId, stepId)` replays stored reply | M (mostly mechanical) |
| `OrderProjectorTest` (11), `OrderSearchProjectorTest` (4) | mock `DomainEventEnvelope` | Invoke `@EventHandler` methods with event + sequence number; stale-sequence discard tests keep their meaning | S |
| `NotificationEventConsumerTest` (6) | `DomainEventEnvelope` | Same as projectors; add duplicate-message-id test | S |
| `CorrelationMessageInterceptorTest` (7) | Tram `Message` | Rewrite against Axon interceptors (`GenericEventMessage`/`GenericCommandMessage` + `MetaData`) | S |
| `SharedEventsContractTest` (2 + parameterized) | round-trips through Eventuate `JSonMapper`, "plain JSonMapper rejects Instant" control | Round-trip through Axon `JacksonSerializer`; replace the JSonMapper control test with: every Order command has `@TargetAggregateIdentifier`, every participant command has `@RoutingKey`, every saga reply carries `orderId`. Update the contract file for the new fields | M |
| `OrderCommandControllerTest` (17) | mocks `OrderCommandService` | Unchanged if the service facade is kept; small changes if controller calls `CommandGateway` directly | S |
| catalog (8 files), gateway (2), ott (2), domain tests (billing/inventory/fulfilment), `CorrelationFilterTest`, `CatalogClientTest`, query controller tests | — | **No change** | — |

### 7.2 Integration tests (Testcontainers, `-Pit`)

| Test | Change | Size |
|---|---|---|
| `AbstractOrderIntegrationTest` + `AbstractIntegrationTest` in inventory, billing, fulfilment | Drop `KafkaContainer`, the `01-eventuate-schema.sql` copy and `eventuatelocal.*` properties; add a shared singleton Axon Server container (`AxonServerContainer` from `axon-test`, or `GenericContainer("axoniq/axonserver")` with dev-mode) and register `axon.axonserver.servers`. Keep Postgres (Flyway now creates Axon tables) | S each |
| `OrderPlacementIT` (16) | Asserts rows in `eventuate.message` and `eventuate.saga_instance` → **rewrite assertions**: events via `EventStore.readEvents(orderId)`, saga via `saga_entry`/`association_value_entry`, first dispatched command via a test-scope recording `@CommandHandler` stub for `ReserveInventoryCommand`. Idempotency-key and "nothing published on rejected order" cases keep intent | **L** |
| `OrderProjectionIT` (8) | Publish events through `EventGateway` (or append to the store) and await Mongo with Awaitility instead of handing envelopes to the projector | M |
| `InventoryConcurrencyIT` (4) | Replace `CommandMessage` mocks + `REPLY_OUTCOME` headers with direct handler calls and captured events; race logic and DB assertions unchanged | M |
| `FulfilmentProvisioningIT` (11), `NotificationDeliveryIT` (3) | Envelope/reply type changes only | S |
| `BillingSeedIT`, `InventorySeedIT`, `OrderPersistenceIT`, `OrderApiSecurityIT`, `OrderActuatorSecurityIT` | Base-class change only (`OrderPersistenceIT` may be retired/reworked if Order becomes event-sourced and `orders.orders` is dropped) | S |
| `OttSecurityIT`, `GatewaySecurityIT`, catalog ITs | None (DB username only if renamed) | — |
| **New** `PlaceOrderSagaIT` | order-service + Axon Server + in-JVM stub participants (test `@CommandHandler`s publishing scripted replies): PAY_NOW happy, BTM happy, capture decline → `FAILED`, pre-fulfil cancel → `CANCELLED_REFUNDED`, provisioning park → `FULFILMENT_FAILED`. Covers the wiring the fixture test can't (serialization, association, saga store, deadlines firing) | M |
| **New** `DeadLetterIT`, `ProjectionReplayIT` | poison event lands in DLQ and processing continues; reset `order-view` and rebuild Mongo from the store | S each |

### 7.3 End-to-end tests (`e2e-tests`, REST Assured, `-Pe2e`)

The 12 e2e classes (~50 tests) are **black-box over HTTP** — they are the regression oracle and
their **assertions should not change**.

* `E2EBase`: update the async-path comment and timeouts (no CDC polling hop; latency drops —
  keep generous timeouts initially, tighten after measuring).
* Stack bring-up: compose files, Jenkins local-CI and EKS smoke stage now start Axon Server
  instead of Kafka/ZK/CDC; health gates change (`/actuator/health` of Axon Server on 8024).
* Demo triggers (amount 999 authorize decline, 777 capture decline, OTT failure offers) are
  participant logic → unchanged; `OrderFailurePathsE2E`, `OrderCancelE2E`,
  `OrderFulfilmentFailedE2E` must pass **unchanged** — that is the acceptance criterion.
* **New e2e**: participant-down timeout (stop billing → order reaches `FAILED` after deadline
  + retries); Axon Server restart mid-flow (order still completes).
* Postman collection (`testing/`) and `TEST-DATASETS.md`: no API change; update any setup steps
  mentioning CDC/Kafka.

### 7.4 Test-effort summary

| Layer | Files changed | Rewrites | New |
|---|---|---|---|
| Unit | ~14 | `PlaceOrderSagaTest`, `OrderCommandServiceTest`/`OrderTest` split, `SharedEventsContractTest` | aggregate fixture tests, idempotency tests |
| Integration | ~11 | `OrderPlacementIT` | `PlaceOrderSagaIT`, `DeadLetterIT`, `ProjectionReplayIT` |
| e2e | `E2EBase` + harness/pipelines | none | timeout + Axon Server restart scenarios |

---

## 8. Effort breakdown

| Phase | Days |
|---|---|
| 0 Baseline & spike | 1–2 |
| 1 Platform & build | 1–2 |
| 2 shared-events / observability (+ contract test) | 2 |
| 3 Participants (+ unit/IT) | 3–4 |
| 4 Order aggregate & service (+ unit/IT) | 3–4 |
| 5 PlaceOrderSaga (+ fixture matrix, saga IT) | 4–6 |
| 6 Projections & notification (+ tests) | 2 |
| 7 Deployment (compose, k8s, EKS, Jenkins) | 2–3 |
| 8 Hardening drills, e2e additions, docs | 1–2 |
| **Total** | **≈ 18–26** |

A cheaper variant (**~12–16 d**) is possible by keeping the Order aggregate state-stored (skip
D2's event-sourcing), skipping deadlines/DLQ/replay, and accepting the dual-write window — but it
loses most of what makes the switch worth doing.

---

## 9. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Saga logic regressions (compensation no longer framework-enforced) | Exhaustive `SagaTestFixture` matrix written before wiring; unchanged e2e failure-path suite as oracle |
| Dual write in participants (JPA + Axon Server) | Idempotent handlers keyed on `(orderId, stepId)` + deadline-driven re-send |
| Axon Server CE single node (SPOF) | Acceptable for this project; note EE clustering / AxonIQ Cloud as the production answer in DD-33 |
| Lost in-flight sagas at cut-over | No data migration: drain (no orders `PLACED`/`CONFIRMED` in flight), then switch. Historical orders stay readable via Mongo; if the Order is event-sourced, completed historical orders are **not** back-filled as events (document it) |
| Serialization drift between services | `SharedEventsContractTest` on Axon's `JacksonSerializer`; `@Revision` + upcasters for future changes |
| Tracing gaps across async hops | Verify trace continuity in Tempo during the Phase 0 spike before committing to D1 |
| Learning curve | Phase 0 spike on one participant; keep the 15-step table in Design/04 as the spec |

---

## 10. Definition of done

* `grep -ri eventuate` over `source/vabags_base` returns only Design history / ADRs.
* `mvn verify -Pit` green; `-Pe2e` green with **unchanged** e2e assertions vs the Phase 0 baseline.
* New tests in place: saga fixture matrix, aggregate fixture, `PlaceOrderSagaIT`, DLQ, replay,
  timeout and Axon Server restart e2e.
* Compose, k3d and EKS stacks start without Kafka / ZooKeeper / CDC.
* DD-33 written; Design 04 / 07 / 11 / 12 / 13 updated.
