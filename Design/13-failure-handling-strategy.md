# 13 — Messaging & Saga Failure-Handling Strategy

> Status: **decided, not implemented** (2026-11-03). Code-level sketch: `13a-failure-handling-code-sketch.md`.
> Once implemented, this becomes DD-33 (message retry + DLQ), DD-34 (timeouts + liveness) and a revision of DD-27
> (deferred-reply provisioning, auto-refund, no admin re-drive) in `08-design-decisions.md`.
> Scope: every Eventuate Tram consumer (5 services), the PlaceOrderSaga, and the synchronous downstream calls they make.

## 1. Goal and invariants

1. **A consumer never dies because of one message.** Every message ends in exactly one of three states:
   **processed**, **retried**, or **parked** (with an alert). A dead subscription is a bug, not a failure mode.
2. **Only business outcomes drive the saga.** Declines and out-of-stock are typed replies, as today. A technical error
   (a DB blip, a bug, a timeout) must never turn into a compensation, and above all not after the pivot.
3. **When infrastructure is down, wait; don't park.** If Postgres, Mongo or Kafka is unavailable, processing pauses
   and resumes by itself.
4. **A slow downstream never blocks a consumer.** Remote calls have timeouts and a circuit breaker. Waiting for a
   remote service to come back happens in a task table, not in a consumer thread.
5. **No silent stall.** A dead consumer, a parked message, an outbox backlog and a long-waiting provisioning task each
   have a metric and an alert.
6. **Each service writes only its own tables.** It tells other services by message, in the same transaction.
7. **Retries are safe because every effect is idempotent.** Local effects are covered by Tram dedupe in the same
   transaction; external effects need an idempotency key (OTT: `order_id`).

## 2. How it behaves today (verified in the sources of the versions we build with)

Versions are pinned by `eventuate-platform-dependencies` 2026.0.RELEASE: **tram-core 0.37.0**, **tram-sagas 0.26.0**,
**messaging-kafka 0.21.0**, **eventuate-common 0.21.0** (F12). Re-check this table when the platform BOM changes.

| # | Fact | Source | Consequence |
|---|---|---|---|
| F1 | One message = one DB transaction: `received_messages` insert → handler → outbox inserts → `saga_instance` update | `SqlTableBasedDuplicateMessageDetector.doWithMessage` | Retrying *inside* the handler can't work (the tx is already rollback-only). Retry must wrap the whole transaction |
| F2 | **Any exception from a participant command handler kills the consumer.** `CommandDispatcher` catches it and calls `findExceptionHandler`, which in 0.37 **throws `UnsupportedOperationException`**. No `Failure` reply is sent | `CommandDispatcher.handleException`, `CommandHandlers.findExceptionHandler` | billing/inventory/fulfilment: one DB blip → subscription dead until restart |
| F3 | The command is deserialised and `"No method for …"` is raised **outside** the try block | `CommandDispatcher.messageHandler` | A poison message kills the consumer, **and again after every restart** (crash loop) |
| F4 | Saga reply handling (`SagaManagerImpl.handleReply`) has no try/catch | `SagaManagerImpl` | An `onReply` exception, an optimistic-lock conflict or a duplicate outbox id kills `PlaceOrderSaga-consumer` → **all** sagas stall |
| F5 | A saga **local step** that throws any `RuntimeException` is treated as a step failure → **compensation**, because `rollbackExceptions` is empty unless `onExceptionRollback` is used | `LocalStep.makeStepOutcome` | A post-pivot local step that throws from saga code compensates backwards: it **releases inventory without a refund**. Through a `@Transactional` service method it instead poisons the outer tx and kills the consumer |
| F6 | Event handlers (`DomainEventDispatcher`) let exceptions propagate | `DomainEventDispatcher.messageHandler` | Projector or notification dies → stale read model |
| F7 | A dead consumer: `SwimlaneDispatcher` logs "terminating" → `MESSAGE_HANDLING_FAILED`, consumer closed. No retry, no restart, no health signal; offsets after the failure are not committed | `EventuateKafkaConsumer`, `SwimlaneDispatcher` | A JVM restart heals it (the message is redelivered); nothing restarts it automatically |
| F8 | Handlers run on swimlane threads, one lane per partition by default, so processing is strictly serial. Back-pressure defaults to `high = Integer.MAX_VALUE` | `MessageConsumerKafkaConfiguration`, `BackPressureConfig` | A handler that blocks holds every message behind it. Fixed by invariant 4, not by more lanes |
| F9 | Eventuate sagas have **no step timeouts** | — | A saga can wait at a step indefinitely. `04-saga-design.md` "Participant timeout → step timeout fires" is wrong (doc drift) |
| F10 | Decorator chain order: tracing `ObservationMessageConsumerDecorator` 0 (reads `DESTINATION` outside its try) → PrePostReceive 100 → **DuplicateDetecting 200** (opens the tx) → PrePostHandler 300 → handler. Decorators are plain beans, sorted by `getOrder()` | `BuiltInMessageHandlerDecoratorOrder`, `DecoratedMessageHandlerFactory` | A decorator at **150** wraps the whole transaction, so each retry gets a fresh tx and re-runs dedupe. A message with no `ID`/`DESTINATION` would fail at 0, before it: `MessageEnvelopeGuard` at **−100** parks those |
| F11 | **Deferred replies are supported.** `onMessage(Class, BiConsumer<CommandMessage, CommandReplyToken>)` returns no reply; `CommandReplyToken` is Jackson-serialisable; `CommandReplyProducer.sendReplies(token, …)` can be called later from any tx | `SagaCommandHandlersBuilder`, `CommandReplyToken`, `CommandReplyProducer` | A participant can hold the saga's reply until a long-running piece of work finishes |
| F12 | **Outbox sends join the caller's tx.** Commands, replies and events are all an `INSERT eventuate.message` through `JdbcTemplate` on the transaction's connection | `MessageProducerJdbcImpl` (tram), `EventuateCommonJdbcOperations.insertIntoMessageTable`, `EventuateSpringJdbcStatementExecutor` (common) | "State change + message" is atomic under `@Transactional`, as long as it's one DataSource and no self-invocation |
| F13 | Domain events are keyed by aggregate id (`PARTITION_ID = aggregateId`); commands and replies get a random UUID | `EventUtil`, `MessageHeaderUtils` | Per-order event ordering holds at any partition count; commands have no ordering (fine: one in-flight message per saga) |

**Bottom line:** today every unexpected exception ends as a dead consumer (F2–F7). The saga state stays correct but
stalls, and post-pivot local steps can compensate wrongly (F5).

## 3. Failure taxonomy

| Class | Examples | Handled by | Then |
|---|---|---|---|
| **CONTENTION** (retrying fixes it) | `OptimisticLockingFailureException`, `PessimisticLockingFailureException`, SQLState `40001` / `40P01` / `55P03`, duplicate key on **`message_pkey`**, Hikari pool exhausted | Decorator: **blocking** retry, 5 attempts, 50 ms × 2ⁿ + jitter (~2 s) | Park |
| **INFRA_DOWN** (our own infrastructure) | DB connection refused, SQLState class `08` / `57P01` / `53300`, Mongo socket/timeout | Decorator: **blocking, unbounded** wait, 1 s → 30 s cap | Never park. Readiness DOWN after 60 s, alert after 5 min |
| **DOWNSTREAM_UNAVAILABLE** (remote service) | OTT / Keycloak 5xx, timeout, breaker open | **The participant**, not the decorator: OTT → deferred-reply provisioning task (§6) | Deadline → business reply (refund) |
| **POISON / BUG** (non-transient technical) | Jackson mapping errors, `"No method for"`, NPE, `IllegalStateException`, anything unclassified | Decorator: 3 blocking attempts | Park + alert |
| **BUSINESS** (not an exception) | decline, out of stock, over limit, delivery failed, **OTT 422** | Typed reply, as today | Saga branch (compensate before the pivot, forward-recover after) |

The classifier is a small, pure, unit-tested function (13a §9). It walks the cause chain. Pool exhaustion vs a dead DB
is decided by whether a connection-refused cause is present.

## 4. Message-level resilience: `ResilientMessageHandlerDecorator`

One Tram `MessageHandlerDecorator` at **order 150** (F10), auto-configured in every Tram service by a new
`shared-messaging` module:

```
accept(msg, chain):
  loop:
    try   chain.invokeNext(msg); return              // fresh tx each time (dedupe at 200 is inside)
    catch e:
      INFRA_DOWN  → sleep(backoff, cap 30 s); retry forever
      CONTENTION  → up to 5 attempts, then park
      POISON/BUG  → up to 3 attempts, then park
      return normally after parking                  // offset committed, consumer keeps going
```
- Blocking retries keep per-partition ordering. CONTENTION/BUG retries last seconds; INFRA_DOWN blocks by design.
- Covers every consumer type (participants, saga replies, projectors, notification), because it wraps the message.
- `MessageEnvelopeGuard` (order −100) parks a message missing `ID` or `DESTINATION` before tracing reads them.
  A message without `DESTINATION` is stored as **`UNROUTABLE`**: its channel is unknown, so it can only be discarded.
  Only a record not written by the outbox can lack them. A record that isn't JSON still fails earlier, in
  `EventuateTramKafkaMessageConsumer`, outside every decorator.
- Metrics: `vab_messaging_retries_total{subscriber,class}`, `vab_messaging_parked_total{subscriber,class}`,
  `vab_messaging_infra_wait_seconds{subscriber}`.
- The liveness health check (§9) restarts the pod if a consumer dies anyway.

## 5. Saga rules (PlaceOrderSaga)

1. **Local steps compensate only on purpose.** Add `.onExceptionRollback(SagaRollback.class)` to every `invokeLocal`
   (steps 6, 9, 10, 15). Step 6 still rolls back on a pre-pivot cancel. Any other exception propagates to the
   decorator instead of compensating (fixes F5).
2. **Participants never reply with a generic failure.** Business outcome = typed reply; technical error = exception for
   the decorator. After the pivot, every reply is `withSuccess` (forward-only).
3. **Cancel vs saga race.** `requestCancel` takes `findByIdForUpdate` (`PESSIMISTIC_WRITE`). The saga's own writes
   keep `@Version`; if one loses to a cancel, the decorator's CONTENTION retry re-runs the step, which then sees the
   flag. **Cancel always wins until the order is `COMPLETED`** (13a §5.3).
4. **Stuck-saga watchdog** (needed because of F9): a `@Scheduled` query in order-service finds non-terminal orders
   older than 5 min with no open provisioning task. It sets `vab_saga_stuck{state}` and logs WARN. **No automatic
   re-send**: a re-sent command gets a new message id, so dedupe wouldn't catch it. Recovery = re-drive the parked
   message (§7).
5. **New step = drain first.** Sagas store their position as a step index, so adding or removing a step requires a
   deploy with no sagas in flight.

## 6. DIGITAL_SUBSCRIPTION provisioning (revises DD-27)

Full sketch: 13a §1–§7.

- **First attempt inline** in the fulfil handler, through a circuit breaker with 2 s / 5 s timeouts and no in-call
  retries.
  - Success → `OrderFulfilled`.
  - OTT **422** → `OrderFulfilmentFailed("PROVISIONING_REJECTED")` straight away → refund.
  - **Unavailable** → no reply; a row in fulfilment-service's own `fulfilment.provisioning_task` keeps the saga's
    reply token (F11). The saga simply waits at step 11 (F9) and the order stays `CONFIRMED`.
- **Poller** (always-on `@Scheduled`, `SKIP LOCKED` + lease, safe with several replicas):
  - Pass 1 runs on every tick, breaker or not: cancel requested → `OrderProvisioningCancelled`; deadline (2 h) passed →
    `OrderFulfilmentFailed("PROVISIONING_TIMEOUT")`.
  - Pass 2 runs only when the breaker isn't open: claim due rows → call OTT **outside** any DB tx → finish in a short tx
    (success, 422 or back off). The reply is sent in the same tx that closes the row, so there is exactly one reply.
- **User cancel** (`POST /{id}/cancel`): order-service sets the flag and, for a digital `CONFIRMED` order, sends
  `CancelProvisioningCommand` in the same tx. Fulfilment marks the task (or leaves a tombstone if the fulfil command
  hasn't arrived yet).
- **Every refund path is the saga's existing forward-recovery:** reply handler sets `forwardRecover` → refund/reverse →
  release → `CANCELLED_REFUNDED`.
- **No subscription left behind after a refund:**
  - An uncertain or overtaken OTT call leaves the task as `REVOKE_OWED`; it's retried with OTT's new idempotent
    `DELETE /by-order/{orderId}`.
  - A cancel that lands after `OrderFulfilled` is caught in `handleOrderFulfilled`, which adds one revoke step before
    the refund.
- **Removed:** the DD-27 park state `FULFILMENT_FAILED`, the admin re-drive / complete endpoints, the
  `OrderProvisioningFailed` reply and the admin alert (13a §10). There is no admin reconcile.

## 7. DLQ (`eventuate.parked_message`) and re-drive

- **DB table, not Kafka dead-letter topics:**
  - Eventuate has no DLT support (Spring Kafka's `@RetryableTopic` doesn't apply).
  - The table is queryable next to `saga_instance` and `orders`.
  - No extra topics.
- **DLQ only.** The decorator writes it for POISON/BUG and exhausted CONTENTION. Nothing is scheduled from it.
- **Admin API in order-service** (back-office role, DD-31): `GET /v1/ops/messages/parked`
  (+ `/count`, `/{id}`), `POST …/{id}/redrive`, `POST …/{id}/discard`. A re-drive is one transaction: `INSERT eventuate.message` (copied
  headers/payload, new id, header `vab-redrive-target=<subscriberId>`) + `status='REDRIVEN'` (F12).
  - Every decorator acks and skips a message whose target is another subscriber, so other consumers of a shared topic
    don't see it twice.
  - Safe because a parked message never committed its effects.
  - **Guard:** if the service dies between park and offset commit, Kafka redelivers the original, which may then
    succeed. So `redrive` first checks `received_messages` for (subscriber, original id). If it's there, nothing is
    sent and the row becomes `PROCESSED` (the copy's new id would bypass Tram dedupe).
  - **Statuses:** `PARKED` (re-drive or discard), `UNROUTABLE` (no `DESTINATION` header, so discard only: re-drive
    is a 409), then `REDRIVEN`, `DISCARDED` or `PROCESSED`. `/count` reports `parked` and `unroutable`.

## 8. Timeouts, downstream restarts, consumer config

### 8.1 Timeouts (all bounded)
| Dependency | Setting | Value |
|---|---|---|
| HTTP clients (fulfilment → OTT/Keycloak, order → catalog) | `spring.http.client.connect-timeout` / `read-timeout` | 2 s / 5 s |
| OTT, Keycloak | Resilience4j circuit breaker (50 % over 10 calls, open 20 s, 1 half-open probe) | — |
| Hikari | `connection-timeout` | 3 s |
| Postgres per session | `options=-c statement_timeout=10000 -c lock_timeout=3000` in the JDBC URL | 10 s / 3 s |
| Mongo | `serverSelectionTimeoutMS` / `socketTimeoutMS` | 3 s / 5 s |
| Gateway | `response-timeout` (exists) | 45 s |
| Shutdown | `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase` | 20 s |

### 8.2 "What if X is restarting?"
| Down / restarting | What happens | Data loss? |
|---|---|---|
| Participant service (billing, inventory, fulfilment) | Commands queue in Kafka; the saga waits (no step timeout). An in-flight message rolls back, is redelivered and deduped | No |
| order-service | POSTs return 503 at the gateway (clients retry with `Idempotency-Key`, DD-07); replies queue | No |
| Postgres | Every consumer goes into INFRA_DOWN wait; readiness DOWN; CDC stops; everything resumes by itself | No |
| Kafka | HTTP writes still succeed (outbox only); CDC and consumers reconnect; outbox backlog → alert | No |
| CDC (`vab-cdc`) | Outbox `published=0` grows → alert on count and oldest age; sagas resume when it's back | No |
| OTT / Keycloak | Breaker opens; digital orders wait in `provisioning_task` up to 2 h, then refund. Physical/licence unaffected | No |
| catalog | Placement fails closed with 503 (DD-32), quickly thanks to the 5 s timeout | No order created |
| Mongo | Projector infra-waits; queries serve the stale view on a hit (DD-15) | No |

### 8.3 Partitions, lanes, consumer properties
- **Eventuate defaults:** 1 partition per topic locally, 1 lane per partition. With invariant 4 no handler blocks for
  long, so extra lanes aren't needed. Revisit only if consumer lag shows it.
- **EKS:** pick a partition count that's a multiple of the replica count (for example 6) when scaling out. Event order
  per order is kept by the key (F13). Partitions can't be reduced later.
- `eventuate.local.kafka.consumer.back-pressure.high=500` / `low=100`; `properties.max.poll.records=100`. Keep the
  defaults for `session.timeout.ms` and `auto.offset.reset=earliest`.
- Hikari pool ≥ concurrent message handlers + web threads + scheduler + 2.

## 9. Visibility and self-healing

- **Eventuate subscription health indicator:** each `EventuateKafkaConsumer.getState()` (read by reflection) goes into
  the **liveness** group, so k8s restarts the pod on `MESSAGE_HANDLING_FAILED` / `FAILED`. It fails open (UNKNOWN) if
  the reflection breaks after an upgrade. INFRA_DOWN wait goes to **readiness**.
- **Alerts** (Grafana/Loki):
  - Consumer group with lag and no members.
  - `parked_message` rows in `PARKED` or `UNROUTABLE` > 0.
  - `vab_saga_stuck` > 0 for 5 min.
  - Outbox oldest unpublished > 60 s.
  - Provisioning: open tasks / oldest age, `REVOKE_OWED` count and age, and the rejected rate in
    `vab_provisioning_outcome_total{outcome}`.
  - Loki: `Exception handling message - terminating`.
- Add `io.eventuate` at WARN to the file appender (today Eventuate errors reach the console only).
- `ObservationMessageProducerInterceptor.postSend` "No such header: ID" masks the real send error. Document it in the
  RCA runbook.

## 10. Rollout and tests

| Phase | Items | Tests |
|---|---|---|
| **A — stop the bleeding** | `shared-messaging`: classifier + decorator + `parked_message` + DLQ admin API; `onExceptionRollback(SagaRollback)` on local steps; timeouts (§8.1); subscription health indicator; `io.eventuate` in the file log | Unit: classifier table; decorator with a fake chain (retry counts, park on exhaustion, infra-wait never parks). IT (Testcontainers PG): two optimistic-lock failures → processed once with one reply; poison → parked **and the next message still processed**; `docker pause` Postgres → consumer resumes |
| **B — provisioning** (needs A) | Breaker on OTT/Keycloak; `provisioning_task` + poller + deferred replies; `CancelProvisioningCommand`; OTT `DELETE /by-order`; saga cancel checkpoint + revoke step (drain sagas first); remove DD-27 park/re-drive; DD-27 revision | Unit: task state machine, deadline, backoff, each cancel timing in 13a §5.3. IT: deferred reply reaches the saga; exactly-once reply under lease reclaim; `ProvisioningFinishAtomicityIT`. E2E: `OTTDOWN` (short deadline) → refund; `OTTBAD` → refund; cancel during the wait → refund |
| **C — scale and docs** | Stuck-saga watchdog; outbox/CDC alerts; DD-33/34; fix the `04-saga-design.md` timeout drift | E2E chaos: restart billing mid-run; stop OTT 2 min; pause Postgres 30 s → every order reaches a terminal state |

## 11. Decision record

| # | Decision | Outcome |
|---|---|---|
| D1 | DLQ store | **DB table** `eventuate.parked_message` (§7) |
| D2 | Delayed / non-blocking retry | **Not a generic mechanism.** Downstream outages are handled by the participant's own task table (OTT: §6); all other retries are blocking in the decorator |
| D3 | Where the code lives | Decorator + classifier in a new **`shared-messaging`** module; DLQ admin API in order-service; provisioning in fulfilment-service |
| D4 | Liveness restarts the pod on a dead subscription | **Yes**, shipped with or after the decorator |
| D5 | Partitions / lanes | **Eventuate defaults**; no custom lane mapping |
| D6 | Cancel race | Pessimistic lock on `requestCancel` + decorator retries on the saga side. Cancel wins until `COMPLETED` |
| D7 | Retry budgets | CONTENTION 5 (~2 s), BUG 3, INFRA_DOWN unbounded; provisioning deadline 2 h, backoff 30 s → 10 m. All configurable |
| D8 | OTT failure handling | Deferred reply + poller; 422 and deadline → refund; **no admin reconcile, no park state** |
