package com.vab.fulfilment.provisioning;

import com.vab.events.common.ProductType;
import com.vab.events.fulfilment.OrderFulfilled;
import com.vab.events.fulfilment.OrderFulfilmentFailed;
import com.vab.events.fulfilment.OrderProvisioningCancelled;
import com.vab.fulfilment.command.OttProvisioningService;
import com.vab.fulfilment.command.OttProvisioningService.Attempt;
import com.vab.fulfilment.domain.FulfilmentRecord;
import com.vab.fulfilment.domain.FulfilmentRecordRepository;
import io.eventuate.tram.commands.consumer.CommandReplyProducer;
import io.eventuate.tram.messaging.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;

/**
 * State changes of provisioning tasks (Design/13 §6), each in one DB transaction together with the saga
 * reply it sends. The reply is sent exactly once: only the transaction that takes a row out of
 * RETRYING / IN_PROGRESS sends it, and the reply's outbox insert commits or rolls back with that change.
 *
 * <p>Every reply is {@code withSuccess} (post-pivot: the saga forward-recovers, it never compensates).
 * The poller calls these methods through the Spring proxy, so {@code @Transactional} applies.
 */
@Service
public class ProvisioningTaskService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningTaskService.class);

    private final ProvisioningTaskStore tasks;
    private final FulfilmentRecordRepository records;
    private final CommandReplyProducer replies;
    private final ProvisioningProperties props;
    private final ProvisioningMetrics metrics;

    public ProvisioningTaskService(ProvisioningTaskStore tasks, FulfilmentRecordRepository records,
                                   CommandReplyProducer replies, ProvisioningProperties props,
                                   ProvisioningMetrics metrics) {
        this.tasks   = tasks;
        this.records = records;
        this.replies = replies;
        this.props   = props;
        this.metrics = metrics;
    }

    /** Pass 1: answer cancelled / timed-out RETRYING rows. Needs no OTT call, so it runs even while OTT is down. */
    @Transactional
    public int resolveDecisions() {
        List<ProvisioningTask> due = tasks.lockDecisionsDue(props.getBatchSize());
        for (ProvisioningTask t : due) {
            close(t, closingReply(t), t.ottUncertain());
        }
        return due.size();
    }

    /** Pass 2, step 1: claim due rows for this poller instance (short tx; the OTT call happens outside it). */
    @Transactional
    public List<ProvisioningTask> claimDue(String owner) {
        return tasks.claimDue(props.getBatchSize(), owner, props.getLease());
    }

    /**
     * Pass 2, step 3: apply an attempt's outcome. Re-reads the row under its lock, so a cancel that arrived
     * during the HTTP call wins (even over a success, which then gets revoked).
     */
    @Transactional
    public void finish(ProvisioningTask claimed, Attempt a, String owner) {
        Optional<ProvisioningTask> locked = tasks.lockIfOwned(claimed.orderId(), owner);
        if (locked.isEmpty()) {
            log.warn("Provisioning lease lost before finishing: orderId={} (another poller reclaimed it)", claimed.orderId());
            return;
        }
        ProvisioningTask row = locked.get();
        boolean uncertain = row.ottUncertain() || a.mayHaveProvisioned();

        if (row.cancelRequested()) {
            close(row, closingReply(row), uncertain);
            return;
        }
        switch (a.outcome()) {
            case ACCEPTED -> {
                tasks.toAwaiting(row.orderId(), a.externalRef());
                metrics.outcome("accepted_on_retry");
                log.info("OTT accepted on attempt {}: orderId={}, externalRef={} — waiting for its activation webhook",
                        row.attempts(), row.orderId(), a.externalRef());
            }
            case PROVISIONED -> {
                String ref = OttProvisioningService.newFulfilmentRef();
                records.save(a.toRecord(ref));
                replies.sendReplies(row.replyToken(), withSuccess(a.toOrderFulfilled(ref)));
                tasks.delete(row.orderId());
                metrics.outcome("provisioned_on_retry");
                log.info("Provisioned on attempt {}: orderId={}, externalRef={}", row.attempts(), row.orderId(), a.externalRef());
            }
            case REJECTED -> {
                metrics.outcome("rejected");
                log.warn("Provisioning rejected by OTT on attempt {}: orderId={}, {}", row.attempts(), row.orderId(), a.detail());
                close(row, withSuccess(new OrderFulfilmentFailed("PROVISIONING_REJECTED", a.detail())), row.ottUncertain());
            }
            case UNAVAILABLE -> {
                if (!Instant.now().isBefore(row.deadlineAt())) {
                    close(row, closingReply(row), uncertain);
                } else {
                    Instant next = Instant.now().plus(props.delayAfter(row.attempts()));
                    tasks.backToRetrying(row.orderId(), next, a.reason(), a.detail(), a.mayHaveProvisioned());
                    log.info("Provisioning still unavailable: orderId={}, attempt={}, next={}, {}",
                            row.orderId(), row.attempts(), next, a.detail());
                }
            }
        }
    }

    // ── OTT webhooks (Design/06 §8) ──────────────────────────────────────────

    /**
     * OTT activated the entitlement. Answers the waiting saga, unless the user cancelled meanwhile (then the
     * cancelled reply, and the entitlement is revoked). An activation nobody is waiting for any more (the order
     * already timed out or was cancelled) is revoked too: the customer was refunded.
     */
    @Transactional
    public void onActivated(String orderId, String externalRef, Instant validFrom, Instant validUntil) {
        Optional<ProvisioningTask> locked = tasks.lock(orderId);
        if (locked.isEmpty() || !isOpen(locked.get())) {
            if (records.existsByOrderId(orderId)) {
                log.info("Activation webhook for an already fulfilled order: orderId={}", orderId);
            } else {
                owesRevoke(orderId);
                log.warn("Late OTT activation, order no longer waiting: orderId={}, externalRef={} — revoke owed",
                        orderId, externalRef);
            }
            return;
        }
        ProvisioningTask row = locked.get();
        if (row.cancelRequested()) {
            close(row, closingReply(row), true);
            return;
        }
        String ref = OttProvisioningService.newFulfilmentRef();
        records.save(new FulfilmentRecord(ref, orderId, ProductType.DIGITAL_SUBSCRIPTION, null, null, externalRef));
        replies.sendReplies(row.replyToken(), withSuccess(new OrderFulfilled(orderId,
                ProductType.DIGITAL_SUBSCRIPTION.name(), ref, null, null, externalRef, validFrom, validUntil)));
        tasks.delete(orderId);
        metrics.outcome("activated");
        log.info("OTT activation confirmed: orderId={}, externalRef={}, validUntil={}", orderId, externalRef, validUntil);
    }

    /** OTT rejected the entitlement while activating it: nothing to revoke, the saga refunds. */
    @Transactional
    public void onRejected(String orderId, String reason) {
        Optional<ProvisioningTask> locked = tasks.lock(orderId);
        if (locked.isEmpty() || !isOpen(locked.get())) {
            log.info("Rejection webhook for an order no longer waiting: orderId={}", orderId);
            return;
        }
        ProvisioningTask row = locked.get();
        metrics.outcome("rejected");
        log.warn("OTT rejected during activation: orderId={}, reason={}", orderId, reason);
        replies.sendReplies(row.replyToken(), withSuccess(new OrderFulfilmentFailed("PROVISIONING_REJECTED",
                "OTT rejected the subscription" + (reason == null ? "" : ": " + reason))));
        tasks.delete(orderId);
    }

    private static boolean isOpen(ProvisioningTask t) {
        return t.status() == ProvisioningTask.Status.RETRYING || t.status() == ProvisioningTask.Status.IN_PROGRESS
                || t.status() == ProvisioningTask.Status.AWAITING_ACTIVATION;
    }

    @Transactional
    public int reclaimExpiredLeases() {
        return tasks.reclaimExpiredLeases();
    }

    @Transactional
    public int deleteExpiredTombstones() {
        return tasks.deleteExpiredTombstones();
    }

    /** REVOKE_OWED rows due now; their next attempt is pushed out by {@code revoke-retry} while the caller tries. */
    @Transactional
    public List<String> claimRevokesDue() {
        return tasks.claimRevokesDue(props.getBatchSize(), props.getRevokeRetry());
    }

    @Transactional
    public void revokeDone(String orderId) {
        tasks.deleteRevokeOwed(orderId);
        log.info("Owed OTT revoke done: orderId={}", orderId);
    }

    /**
     * Called inside the Tram tx of a fulfil / release handler, or by {@link #close}: OTT may hold an
     * entitlement for an order being refunded. First revoke after one lease, so an in-flight POST lands first.
     */
    public void owesRevoke(String orderId) {
        tasks.upsertRevokeOwed(orderId, Instant.now().plus(props.getLease()));
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** Cancel wins over the deadline. */
    private Message closingReply(ProvisioningTask t) {
        if (t.cancelRequested()) {
            metrics.outcome("cancelled");
            log.info("Provisioning cancelled by the user: orderId={}", t.orderId());
            return withSuccess(new OrderProvisioningCancelled(t.orderId(), t.cancelReason()));
        }
        metrics.outcome("timeout");
        log.warn("Provisioning deadline passed: orderId={}, attempts={}", t.orderId(), t.attempts());
        return withSuccess(new OrderFulfilmentFailed("PROVISIONING_TIMEOUT",
                "OTT did not provision within " + props.getDeadline()));
    }

    /** Sends the reply and takes the row out of the open states, in the caller's transaction. */
    private void close(ProvisioningTask t, Message reply, boolean uncertain) {
        replies.sendReplies(t.replyToken(), reply);
        if (uncertain) {
            owesRevoke(t.orderId());   // the row stays as REVOKE_OWED until OTT confirms the delete
        } else {
            tasks.delete(t.orderId());
        }
    }
}
