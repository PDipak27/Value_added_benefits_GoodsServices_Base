# 13a — Failure handling: code sketch (companion to 13-failure-handling-strategy.md)

> **Sketch only. Not compiled, no source file changed.** Names follow the existing code. `// NEW` = new code,
> `// CHANGED` = edit. Revision 4 (2026-11-03): deferred-reply provisioning **without the 422/reconcile path and
> without admin reconcile**. A provisioning ends in exactly one of three ways: provisioned, cancelled by the user, or
> failed (OTT rejected it, or the deadline passed). Each service writes only its own tables. Open items are in §11.

## 0. Decisions

| Topic | Decision |
|---|---|
| OTT unavailable (5xx / timeout / breaker open) | No reply. A task row keeps the saga's reply token; a poller retries until the deadline (2 h, configurable). Order stays `CONFIRMED` |
| OTT rejected (422) | **No special path.** Permanent failure: reply `OrderFulfilmentFailed("PROVISIONING_REJECTED")` at once → saga refunds. No task, no admin step |
| Deadline passed | Reply `OrderFulfilmentFailed("PROVISIONING_TIMEOUT")` → saga refunds (DD-27 "no auto-refund" is superseded) |
| User cancel while waiting | The poller replies `OrderProvisioningCancelled` → saga refunds. **Cancel always wins** (§5.3) |
| Admin reconcile / abandon / re-drive | **Removed** (§10) |
| `orders.orders` | **Unchanged**: no new columns, no new status values |
| Cross-service | order-service → fulfilment: `CancelProvisioningCommand` on the existing `fulfilmentService` channel. No new topic |
| Task row | Deleted as soon as it's no longer needed (kept only while a revoke at OTT is still owed) |
| Saga | Waits at step 11. Reply handlers set the existing `forwardRecover` flag; refund/reverse → release → `CANCELLED_REFUNDED` run as today. One new step: revoke after a late cancel (§6) |
| `parked_message` | DLQ only, written by the generic decorator (§9) |
| Swimlanes / partitions | Eventuate defaults |

### Outcomes

| What happened | Task row | Reply to saga | Order ends |
|---|---|---|---|
| Provisioned (first try or by the poller) | none / deleted | `OrderFulfilled` (existing) | `COMPLETED` |
| OTT 422 (first try or by the poller) | none / deleted | `OrderFulfilmentFailed("PROVISIONING_REJECTED")` (existing) | `CANCELLED_REFUNDED` |
| OTT still unavailable at the deadline | deleted (or `REVOKE_OWED`) | `OrderFulfilmentFailed("PROVISIONING_TIMEOUT")` | `CANCELLED_REFUNDED` |
| User cancelled while waiting | deleted (or `REVOKE_OWED`) | `OrderProvisioningCancelled` (new) | `CANCELLED_REFUNDED` |

---

## 1. Flow

```
                     order-service                                   fulfilment-service
                     ─────────────                                   ──────────────────
saga step 11  ── FulfilOrderCommand ───────────────────────────────► fulfil(): first attempt inline
                                                                       OK          → OrderFulfilled now
                                                                       422         → OrderFulfilmentFailed(REJECTED) now
                                                                       UNAVAILABLE → task RETRYING, no reply
user  POST /cancel
        lock order row, cancel_requested=true
        if digital && CONFIRMED ── CancelProvisioningCommand ─────────► task.cancel_requested = true
                                                                       (no task yet → cancel tombstone)

poller tick   Pass 1, no OTT call, runs even when the breaker is open:
                cancel_requested, or deadline passed → reply to saga + delete row
              Pass 2, only when the breaker isn't open:
                due RETRYING rows → attempt → OK: reply OrderFulfilled + delete
                                              422: reply OrderFulfilmentFailed(REJECTED) + delete
                                              UNAVAILABLE: back to RETRYING (backoff)

saga reply handlers  OrderFulfilled → finalize → COMPLETED
                     OrderProvisioningCancelled / OrderFulfilmentFailed → forwardRecover=true
                       → refund or reverseLedger → release → finalize → CANCELLED_REFUNDED
```

**Timeline: OTT is down for 3 minutes, and one customer cancels during the outage**
```
t=0      D1 fulfil → POST times out (5 s) → task D1 RETRYING, deadline t+2h, no reply. Saga waits at step 11.
t=5      P1 (physical), L1 (licence) → fulfilled normally (no OTT call)
t=6..    D2, D3 fail → breaker OPENS → D4 fails in ~1 ms → tasks inserted. The fulfilment consumer is never held.
t=40     customer cancels D2 → order row locked, flag set → CancelProvisioningCommand → task D2 cancel_requested
t=45     poller pass 1 (breaker OPEN doesn't matter): D2 → reply OrderProvisioningCancelled; its earlier attempt failed
         with no uncertainty → row deleted. Saga: refund → release → CANCELLED_REFUNDED
t=50     breaker HALF_OPEN → pass 2 probes D1 → still down → RETRYING, next +1 m
t=180    OTT back → next probe succeeds → breaker CLOSED → D1, D3, D4 provisioned → OrderFulfilled → COMPLETED
2-hour outage: at the deadline each remaining task → OrderFulfilmentFailed(TIMEOUT) → refund.
```

---

## 2. Eventuate supports deferred replies (verified in tram-core 0.37.0 / tram-sagas 0.26.0 sources)

- `SagaCommandHandlersBuilder.onMessage(Class<C>, BiConsumer<CommandMessage<C>, CommandReplyToken>)` registers a
  handler that returns no reply.
- `CommandReplyToken` holds the reply headers and channel, and has a no-arg constructor "For ObjectMapper", so it can
  be stored as JSON on the task row.
- Later, in any transaction: `commandReplyProducer.sendReplies(token, withSuccess(reply))`. `SagaManagerImpl.handleReply`
  applies it to the saga's current state, however late.
- **Every reply here must be `withSuccess`.** These steps are after the pivot; a `withFailure` would run LIFO
  compensation instead of forward-recovery.

---

## 3. order-service: cancel only

```java
// shared-events                                                          // NEW
class CancelProvisioningCommand implements Command { String orderId; String reason; }   // order → fulfilment
class OrderProvisioningCancelled { String orderId; String reason; }                     // reply to the saga
class ReleaseEntitlementCommand implements Command { String orderId; String externalRef; } // saga → fulfilment (§6)

// Order                                                                  // NEW helper, no new fields
public boolean isWaitingOnProvisioning() {
    return "DIGITAL_SUBSCRIPTION".equals(productType) && status == OrderStatus.CONFIRMED;
}

// OrderRepository                                                        // NEW
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select o from Order o where o.id = :id")
Optional<Order> findByIdForUpdate(@Param("id") String id);

// OrderCommandService.requestCancel                                      // CHANGED: one DB tx = flag + command
@Transactional
public void requestCancel(String orderId) {
    Order order = orderRepo.findByIdForUpdate(orderId).orElseThrow(...);
    order.requestCancel();                                                // 409 once terminal (unchanged)
    orderRepo.saveAndFlush(order);
    if (order.isWaitingOnProvisioning()) {
        commandProducer.send(PlaceOrderSaga.FULFILMENT_CHANNEL,
                new CancelProvisioningCommand(orderId, "USER_CANCEL: during provisioning"),
                CANCEL_REPLY_CHANNEL, Map.of());                         // handler sends no reply
    }
}
```
`CONFIRMED` on a digital order means "step 10 has passed, provisioning may be in progress". If provisioning has
already finished, the command is a no-op in fulfilment and the saga's last checkpoint (§6) catches the cancel.

---

## 4. fulfilment-service: table, handlers, breaker

```sql
-- fulfilment-service: V2__provisioning_task.sql                          // NEW
CREATE TABLE fulfilment.provisioning_task (
  order_id          VARCHAR(255) PRIMARY KEY,
  command_json      TEXT,                     -- FulfilOrderCommand   (null for CANCEL_TOMBSTONE)
  reply_token_json  TEXT,                     -- CommandReplyToken    (null for CANCEL_TOMBSTONE)
  status            VARCHAR(24) NOT NULL,     -- RETRYING | IN_PROGRESS | REVOKE_OWED | CANCEL_TOMBSTONE
  cancel_requested  BOOLEAN NOT NULL DEFAULT FALSE,
  cancel_reason     VARCHAR(300),
  ott_uncertain     BOOLEAN NOT NULL DEFAULT FALSE,   -- an earlier call may have created an entitlement
  last_reason       VARCHAR(40),
  last_detail       VARCHAR(300),
  attempts          INT NOT NULL DEFAULT 1,
  next_attempt_at   TIMESTAMPTZ,
  deadline_at       TIMESTAMPTZ NOT NULL,     -- RETRYING: created_at + 2h; tombstone: its expiry
  lease_owner       VARCHAR(64),
  lease_until       TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_task_due      ON fulfilment.provisioning_task (next_attempt_at) WHERE status = 'RETRYING';
CREATE INDEX ix_task_deadline ON fulfilment.provisioning_task (deadline_at);
```
```java
// FulfilmentCommandHandlers                                              // CHANGED
.onMessage(FulfilOrderCommand.class, this::fulfil)                         // BiConsumer overload: no automatic reply
.onMessage(CancelProvisioningCommand.class, this::cancelProvisioning)
.onMessage(ReleaseEntitlementCommand.class, this::releaseEntitlement)      // §6

@Transactional
public void fulfil(CommandMessage<FulfilOrderCommand> cm, CommandReplyToken token) {
    FulfilOrderCommand cmd = cm.getCommand();
    if (tasks.consumeTombstone(cmd.getOrderId())) {                        // the cancel got here first (§5.3)
        replies.sendReplies(token, withSuccess(new OrderProvisioningCancelled(cmd.getOrderId(), "USER_CANCEL")));
        return;
    }
    Message reply = switch (typeOf(cmd)) {                                 // demo FAIL trigger + UNKNOWN type as today
        case PHYSICAL_GOOD        -> fulfilPhysical(cmd);
        case SOFTWARE_LICENSE     -> fulfilLicense(cmd);
        case DIGITAL_SUBSCRIPTION -> fulfilDigital(cmd, token);            // null = deferred
    };
    if (reply != null) replies.sendReplies(token, reply);
}

private Message fulfilDigital(FulfilOrderCommand cmd, CommandReplyToken token) {
    Attempt a = provisioning.attempt(cmd);                                 // HTTP only, no DB writes
    return switch (a.outcome()) {
        case PROVISIONED -> { records.save(FulfilmentRecord.digital(cmd.getOrderId(), a.externalRef()));
                              yield withSuccess(a.toOrderFulfilled()); }
        case REJECTED    -> withSuccess(new OrderFulfilmentFailed("PROVISIONING_REJECTED", a.detail()));
        case UNAVAILABLE -> { tasks.insertRetrying(cmd, token, a); yield null; }   // deadline = now + 2h
    };
}

/** Locks the task row, so it waits for any finish() in progress. */
@Transactional
public void cancelProvisioning(CommandMessage<CancelProvisioningCommand> cm) {
    String orderId = cm.getCommand().getOrderId();
    tasks.lock(orderId).ifPresentOrElse(
        t -> tasks.requestCancel(t, cm.getCommand().getReason()),          // pass 1 or finish() replies
        () -> {
            if (!records.existsByOrderId(orderId)) tasks.insertCancelTombstone(orderId);  // cancel overtook fulfil
            // else: already provisioned → no-op; the saga's last checkpoint (§6) handles it
        });
}
```
- The task insert is inside the Tram message transaction (dedupe + handler). A failed commit means the message is
  redelivered with nothing half-written.
- `OttProvisioningService.attempt()` is **not** `@Transactional`: the HTTP call doesn't hold a DB connection it owns.
  `FulfilmentRecord` is written by whoever applies the result.

### 4.1 Circuit breaker around `OttClient.provision`
```yaml
# fulfilment-service application.yml                                      // NEW
spring.http.client: { connect-timeout: 2s, read-timeout: 5s }
ott.max-attempts: 1                                       # CHANGED from 3: retries live in provisioning_task
resilience4j.circuitbreaker.instances.ott:
  sliding-window-size: 10
  minimum-number-of-calls: 5
  failure-rate-threshold: 50
  slow-call-duration-threshold: 4s
  slow-call-rate-threshold: 80
  wait-duration-in-open-state: 20s
  permitted-number-of-calls-in-half-open-state: 1
  ignore-exceptions: org.springframework.web.client.HttpClientErrorException   # 4xx = OTT is up
vab.fulfilment.provisioning:
  deadline: 2h
  backoff: 30s,1m,2m,5m,10m                               # last value repeats until the deadline
  poll-interval: 5s
  batch-size: 10
  lease: 60s
  tombstone-ttl: 2h
```
```java
// OttClient.provision                                                    // CHANGED
try {
    EntitlementResponse resp = breaker.executeSupplier(() -> restClient.post()…body(EntitlementResponse.class));
    return Result.ok(resp.externalRef());
} catch (CallNotPermittedException open) {                                // no HTTP call: nothing can exist at OTT
    return Result.unavailable("OTT circuit open", /* uncertain */ false);
} catch (ResourceAccessException e) {                                     // refused = certain; read timeout = uncertain
    return Result.unavailable(shortDetail(e), isReadTimeout(e));
} catch (HttpClientErrorException.UnprocessableEntity e) {
    return Result.rejected(shortDetail(e));
} catch (Exception e) {                                                   // 5xx: OTT may have committed
    return Result.unavailable(shortDetail(e), true);
}
// KeycloakTokenProvider gets its own breaker "keycloak"; a token failure → unavailable (certain).
```

---

## 5. Poller

### 5.1 Tick
```java
@Component                                                                 // NEW
class ProvisioningPoller {

    @Scheduled(fixedDelayString = "${vab.fulfilment.provisioning.poll-interval:5s}")
    void tick() {
        tasks.reclaimExpiredLeases();                 // IN_PROGRESS & lease_until < now → RETRYING
        tasks.deleteExpiredTombstones();
        resolver.resolveBatch(batchSize);             // Pass 1: cancel + deadline. Always runs, needs no OTT
        if (breaker.getState() != OPEN) {             // Pass 2: only OTT calls wait for the breaker
            revokes.retryOwed(batchSize);
            for (Task t : tasks.claimDue(batchSize, instanceId, lease)) {   // short tx: RETRYING → IN_PROGRESS, SKIP LOCKED
                Attempt a = provisioning.attempt(t.command());              // HTTP, no DB tx held
                tasks.finish(t, a, instanceId);                             // short tx
            }
        }
    }
}
```
One always-on bean per instance. With nothing due, each pass is one indexed query returning 0 rows. Several replicas
are safe because of `SKIP LOCKED` and the lease. `resolver`, `tasks` and `revokes` are separate beans, so their
`@Transactional` applies.

### 5.2 Pass 1: cancel and deadline (no OTT call)
```java
// ProvisioningResolver                                                    // NEW
@Transactional
public void resolveBatch(int limit) {
    List<Task> rows = jdbc.query("""
        SELECT * FROM fulfilment.provisioning_task
         WHERE status = 'RETRYING'                                    -- IN_PROGRESS is settled by finish()
           AND (cancel_requested OR deadline_at <= now())
         LIMIT ? FOR UPDATE SKIP LOCKED""", TASK_ROW, limit);
    for (Task t : rows) closeWithReply(t, closingReply(t));
}

Message closingReply(Task t) {                                             // cancel first: it always wins
    if (t.cancelRequested()) return withSuccess(new OrderProvisioningCancelled(t.orderId(), t.cancelReason()));
    return withSuccess(new OrderFulfilmentFailed("PROVISIONING_TIMEOUT", "Not provisioned within " + deadline));
}

/** Reply and row change in one tx. Exactly one reply: the row leaves RETRYING/IN_PROGRESS here. */
void closeWithReply(Task t, Message reply) {
    replies.sendReplies(t.token(), reply);
    if (t.ottUncertain()) tasks.markRevokeOwed(t.orderId());              // may exist at OTT: kept until revoked
    else                  tasks.delete(t.orderId());
}
```

### 5.3 Finish an attempt, and why cancel always wins
```java
@Transactional
void finish(Task t, Attempt a, String owner) {
    Task row = tasks.lockIfOwned(t.orderId(), owner).orElse(null);         // re-reads cancel_requested under the lock
    if (row == null) { log.warn("lease lost: {}", t.orderId()); return; }

    boolean uncertain = row.ottUncertain() || a.mayHaveProvisioned();      // a success counts: it has to be revoked
    if (row.cancelRequested()) {                                           // arrived during the HTTP call
        tasks.setUncertain(row, uncertain);
        closeWithReply(row, closingReply(row));
        return;
    }
    switch (a.outcome()) {
        case PROVISIONED -> {                                              // a success beats the deadline
            records.save(FulfilmentRecord.digital(row.orderId(), a.externalRef()));
            replies.sendReplies(row.token(), withSuccess(a.toOrderFulfilled()));
            tasks.delete(row.orderId());
        }
        case REJECTED -> {
            tasks.setUncertain(row, row.ottUncertain());
            closeWithReply(row, withSuccess(new OrderFulfilmentFailed("PROVISIONING_REJECTED", a.detail())));
        }
        case UNAVAILABLE -> {
            tasks.setUncertain(row, uncertain);
            if (now().isAfter(row.deadlineAt())) closeWithReply(row, closingReply(row));
            else tasks.backToRetrying(row, now().plus(backoff.after(row.attempts())), a);
        }
    }
}
```
**Every point where a cancel can land:**

| Cancel lands… | What happens | Result |
|---|---|---|
| Before the saga's step 10 commits | Row lock + `@Version`: the saga step retries (decorator, §9) and sees the flag at the checkpoint | Forward-recover (as today) |
| After step 10, fulfil command not handled yet | `cancelProvisioning` finds no task and no record → tombstone → `fulfil` replies cancelled without calling OTT | Refund |
| Task waiting (`RETRYING`) | `cancel_requested=true` → pass 1 replies within one tick | Refund |
| During an attempt (`IN_PROGRESS`) | `finish` re-reads the flag under the lock; a success gets revoked (§6) | Refund |
| Fulfil replied `OrderFulfilled`, saga not finalized yet | `handleOrderFulfilled` sees the flag → revoke step → refund (§6) | Refund |
| Same moment as the saga's finalize | Order row lock + `@Version`: finalize fails, the decorator retries it and it sees the flag | Refund |
| After `COMPLETED` | 409, as today | Cancel refused |

---

## 6. Undoing a provisioned entitlement

A refund must not leave the customer with the subscription.

**Uncertain or in-flight attempt → task row stays as `REVOKE_OWED`.** A timed-out POST may have created the
entitlement without us ever seeing the `externalRef`, so OTT needs a by-order delete:
```java
// ott-service EntitlementController                                      // NEW
@DeleteMapping("/by-order/{orderId}")                                     // idempotent: 204 even if nothing exists
public ResponseEntity<Void> revokeByOrder(@PathVariable String orderId) { ... }

// fulfilment RevokeRetrier.retryOwed                                     // NEW
// status='REVOKE_OWED' AND updated_at < now() - lease → OttClient.revokeByOrder → delete row on success.
// The lease wait lets an in-flight POST land first. No deadline: alert on age. The refund doesn't wait for this.
```

**Fulfilled, but the cancel came in before the saga finalized → one new saga step.**
```java
// PlaceOrderSagaData                                                     // NEW
private boolean entitlementRevokeRequired;

// PlaceOrderSaga.handleOrderFulfilled                                    // CHANGED: last cancel checkpoint
...existing setters...
if ("DIGITAL_SUBSCRIPTION".equals(data.getProductType())
        && orderCommandService.isCancelRequested(data.getOrderId())) {
    data.setForwardRecover(true);
    data.setEntitlementRevokeRequired(true);
    data.setCancelReason("USER_CANCEL: during provisioning");
}

// PlaceOrderSaga definition                                              // CHANGED: new step straight after fulfil
.step()
    .invokeParticipant(PlaceOrderSagaData::isEntitlementRevokeRequired, releaseEntitlementEndpoint, this::releaseEntitlement)

// FulfilmentCommandHandlers.releaseEntitlement                            // NEW: always replies success
// Tries revokeByOrder. If OTT is down: inserts a REVOKE_OWED row. The refund proceeds either way.
```

---

## 7. Saga reply handlers

```java
// PlaceOrderSaga                                                          // CHANGED
fulfilOrderEndpoint: .withReply(OrderProvisioningCancelled.class)          // OrderProvisioningFailed: removed (§10)

.onReply(OrderFulfilled.class, this::handleOrderFulfilled)                  // + last cancel checkpoint (§6)
.onReply(OrderFulfilmentFailed.class, this::handleOrderFulfilmentFailed)    // existing: REJECTED and TIMEOUT land here
.onReply(OrderProvisioningCancelled.class, this::handleProvisioningCancelled)

void handleProvisioningCancelled(PlaceOrderSagaData data, OrderProvisioningCancelled reply) {
    data.setForwardRecover(true);
    data.setCancelReason(cap(reply.getReason()));
}
// shouldRefund / shouldReverseLedger / isForwardRecover / finalizeOrder: unchanged, they key off forwardRecover.
// finalizeOrder's provisioningFailed → FULFILMENT_FAILED branch: removed (§10).
```

---

## 8. Atomicity: why `@Transactional` is enough here

Tram's "send" (command, reply or event) is an `INSERT` into `eventuate.message`, on the same connection as the rest of
the transaction. Verified chain:
```
CommandReplyProducer.sendReplies / CommandProducer.send / DomainEventPublisher.publish
  → MessageProducerJdbcImpl.send → insertIntoMessageTable
      → EventuateSpringJdbcStatementExecutor.update → JdbcTemplate.update
          → DataSourceUtils.getConnection(ds)   ← the connection bound to the current Spring tx (JPA shares it)
```
So "cancel flag + cancel command" (order-service) and "task change + saga reply" (fulfilment) each commit or roll back
as one unit.

| `@Transactional` does NOT give atomicity when… | Here |
|---|---|
| Sending with `KafkaTemplate` / HTTP inside the tx | Not used. The OTT call is outside `finish` |
| The outbox is in another DataSource / DB | One DB `vab` |
| Self-invocation or a `private` method | Poller calls other beans |
| A checked exception is thrown | Runtime exceptions only |

Proof test: `ProvisioningFinishAtomicityIT`. Call `finish(PROVISIONED)` inside an outer tx that throws, then assert no
reply row, task still `IN_PROGRESS`, no `fulfilment_record`.

---

## 9. Consumer never dies: the generic decorator (required by this design)

§5.3 relies on it: a lost `@Version` race must retry instead of killing the saga consumer.

```java
enum FailureClass { CONTENTION, INFRA_DOWN, POISON }
static FailureClass classify(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
        if (c instanceof OptimisticLockingFailureException || c instanceof PessimisticLockingFailureException) return CONTENTION;
        if (c instanceof EventuateDuplicateKeyException && mentions(c, "message_pkey")) return CONTENTION;
        if (c instanceof SQLException sql) {
            String s = sql.getSQLState();
            if ("40001".equals(s) || "40P01".equals(s) || "55P03".equals(s)) return CONTENTION;
            if (s != null && (s.startsWith("08") || "57P01".equals(s) || "53300".equals(s))) return INFRA_DOWN;
        }
        if (c instanceof java.net.ConnectException) return INFRA_DOWN;
    }
    if (t instanceof CannotGetJdbcConnectionException) return CONTENTION;   // Hikari pool exhausted, DB itself fine
    return POISON;
}

public class ResilientMessageHandlerDecorator implements MessageHandlerDecorator {
    public int getOrder() { return 150; }            // outside DuplicateDetecting (200) → fresh tx per attempt
    public void accept(SubscriberIdAndMessage sm, MessageHandlerDecoratorChain chain) {
        int attempt = 1;
        while (true) {
            try { chain.invokeNext(sm); return; }
            catch (RuntimeException e) {
                FailureClass fc = classify(e);
                if (fc == INFRA_DOWN) { sleep(min(30_000, 1_000L << min(attempt++, 5))); continue; }   // never park
                if (attempt < (fc == CONTENTION ? 5 : 3)) { sleep(jitter(50L << attempt)); attempt++; continue; }
                park(sm, fc, e, attempt);            // eventuate.parked_message, own tx
                return;                              // returns normally ⇒ offset committed, consumer lives
            }
        }
    }
}
// Registration: a @Bean is enough. Tram collects MessageHandlerDecorator beans and sorts by getOrder() (verified).
```
```sql
-- deploy/postgres-init/05-parked-message.sql  (DLQ only)                 // NEW
CREATE TABLE IF NOT EXISTS eventuate.parked_message (
  id BIGSERIAL PRIMARY KEY, subscriber_id VARCHAR(255) NOT NULL, channel VARCHAR(255) NOT NULL,
  message_id VARCHAR(255) NOT NULL, headers_json TEXT NOT NULL, payload TEXT NOT NULL,
  failure_class VARCHAR(32) NOT NULL, error VARCHAR(1000), attempts INT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PARKED', parked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (subscriber_id, message_id));
```

---

## 10. What gets removed

The DD-27 park / admin re-drive feature goes away completely:

| Area | Removed |
|---|---|
| shared-events | `OrderProvisioningFailed` (reply); order event `OrderFulfilmentFailed` (the reply class of the same name in `events.fulfilment` stays) |
| fulfilment-service | The `OrderProvisioningFailed` branch in `fulfilDigital`; `OttClient` in-call retries |
| order-service saga | `handleOrderProvisioningFailed`, `provisioningFailed` / `provisioningFailureReason` saga data, the finalize → `FULFILMENT_FAILED` branch |
| order-service | `FulfilmentReDrive` + subscription `orderServiceFulfilmentReplies` (channel `orderService-fulfilment-replies`), `retryFulfilment` / `completeFromReDrive` / `parkFromReDrive` / `completeFulfilment` / `fulfilmentFailed`, `POST /{id}/retry-fulfilment` + `/complete-fulfilment` (and their `SecurityConfig` entries), `Order.fulfilmentFailed`, `OrderStatus.FULFILMENT_FAILED` |
| Read side / notification | `FULFILMENT_FAILED` branches in `OrderProjector` / `OrderSearchProjector`; `onOrderFulfilmentFailed` + `ORDER_FULFILMENT_FAILED` admin alert |
| Kafka | Topic `orderService-fulfilment-replies` (from `createKafkaTopics.ps1`) |
| Tests | `FulfilmentReDriveTest`, `OrderCommandServiceTest.ReDrive`, DD-27 e2e. The `OTTBAD` e2e now expects `CANCELLED_REFUNDED` |
| Kept | Admin entitlement revoke (Phase 3: `EntitlementRevoke`, `/revoke-entitlement`). It's a different feature |
| Kept, harmless | DB column `orders.last_attempt_at` (V5). Drop it in a later migration if you want |

---

## 11. Open items / gaps

| # | Item | Default in this sketch |
|---|---|---|
| 1 | **Existing `FULFILMENT_FAILED` rows have no way out** once the re-drive endpoints are gone. They have no saga waiting, and their charge stands | Dev/e2e data only: wipe the orders, or run a one-off SQL that marks them `CANCELLED_REFUNDED` (no real refund happens). If any environment holds real orders, keep the old endpoints for one release |
| 2 | **No human sees rejections any more.** A misconfigured offer that OTT always rejects would refund every order without anyone being told | Counter `vab_provisioning_outcome_total{outcome}` plus an alert on the rejected rate. The customer still gets the existing `CANCELLED_REFUNDED` notification |
| 3 | **No human sees long waits either** (until the 2 h deadline) | Gauges: open tasks, oldest task age, `REVOKE_OWED` count and age |
| 4 | **Inserting the saga's revoke step shifts step numbers.** Sagas store `currentlyExecuting` as an index, so any saga in flight at deploy would resume on the wrong step | Deploy with no sagas in flight. Local and e2e: restart clean |
| 5 | **`DELETE /by-order` assumes we can change OTT.** True for `ott-service`; a real provider may not offer it | Add it to `ott-service` |
| 6 | **A late POST could still create an entitlement after the revoke** (a request stuck at OTT for longer than the lease wait) | Accepted as very unlikely |
| 7 | **`OTTDOWN` demo offer opens the shared breaker.** It returns 503 for one offer, but the breaker covers all digital orders, so in e2e other orders get deferred. An `OTTDOWN` order also waits the full deadline | e2e profile: `deadline: 30s`, `backoff: 2s` |
| 8 | **Non-422 4xx from OTT** (401 from a bad token, 404) count as unavailable, as they do today, so a config error retries for 2 h and then refunds | Keep; covered by the alerts in #2 and #3 |
| 9 | **Physical and licence orders:** a cancel after step 10 still loses. They're fulfilled straight away, so there's no wait to cancel during | Unchanged |
