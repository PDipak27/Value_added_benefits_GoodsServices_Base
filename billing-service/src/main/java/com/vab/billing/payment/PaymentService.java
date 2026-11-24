package com.vab.billing.payment;

import com.vab.billing.domain.BillingLedgerEntry;
import com.vab.billing.domain.BillingLedgerRepository;
import com.vab.billing.payment.PaymentIntentRecord.Status;
import com.vab.events.billing.AwaitPaymentCommand;
import com.vab.events.billing.BillingCaptureFailed;
import com.vab.events.billing.BillingCaptured;
import com.vab.events.billing.BillingRefunded;
import com.vab.events.billing.CaptureBillingCommand;
import com.vab.events.billing.CreatePaymentIntentCommand;
import com.vab.events.billing.PaymentAuthorized;
import com.vab.events.billing.PaymentIntentCreated;
import com.vab.events.billing.PaymentIntentFailed;
import com.vab.events.billing.PaymentNotCompleted;
import com.vab.events.billing.RefundBillingCommand;
import io.eventuate.tram.commands.consumer.CommandReplyProducer;
import io.eventuate.tram.commands.consumer.CommandReplyToken;
import io.eventuate.tram.messaging.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withFailure;
import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;

/**
 * PAY_NOW payment state (Design/15). Three callers drive one state machine per order: the saga's commands,
 * the provider's webhooks and the {@link PaymentSweeper}. Every method locks the order's row first, so
 * they serialise; a deferred saga reply is sent in the same transaction that changes the row, so it is
 * sent exactly once.
 *
 * <p>Provider calls happen inside these transactions, bounded by the HTTP timeouts and the {@code psp}
 * breaker. A provider outage never blocks a Tram consumer: create and capture fail the step, void and refund
 * become owed and the sweeper retries them.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentIntentStore intents;
    private final PspClient psp;
    private final CommandReplyProducer replies;
    private final BillingLedgerRepository ledger;
    private final PaymentProperties props;

    public PaymentService(PaymentIntentStore intents, PspClient psp, CommandReplyProducer replies,
                          BillingLedgerRepository ledger, PaymentProperties props) {
        this.intents = intents;
        this.psp     = psp;
        this.replies = replies;
        this.ledger  = ledger;
        this.props   = props;
    }

    // ── saga commands ─────────────────────────────────────────────────────────

    /** Idempotent per order: a redelivered command gets the same intent back. */
    @Transactional
    public Message createIntent(CreatePaymentIntentCommand cmd) {
        PaymentIntentRecord row = intents.lockByOrder(cmd.getOrderId()).orElse(null);
        if (row != null) {
            if (row.intentId() == null) {
                return withFailure(new PaymentIntentFailed("CANCELLED", "Cancelled before payment started"));
            }
            return withSuccess(new PaymentIntentCreated(row.intentId(), row.checkoutUrl(), row.expiresAt()));
        }

        PspClient.IntentResult r = psp.createIntent(cmd.getOrderId(), cmd.getAmount(), cmd.getCurrency());
        if (r.outcome() != PspClient.Outcome.OK) {
            String reason = r.outcome() == PspClient.Outcome.REJECTED ? "PSP_REJECTED" : "PSP_UNAVAILABLE";
            log.warn("Payment intent NOT created: orderId={}, {} {}", cmd.getOrderId(), reason, r.detail());
            return withFailure(new PaymentIntentFailed(reason, r.detail()));
        }
        if (!intents.insertCreated(cmd.getOrderId(), r.intentId(), cmd.getSubscriberId(), cmd.getAmount(),
                cmd.getCurrency(), r.checkoutUrl(), r.expiresAt())) {
            // A cancel landed while the provider call was in flight: don't leave a payable intent behind.
            psp.cancel(r.intentId());
            return withFailure(new PaymentIntentFailed("CANCELLED", "Cancelled before payment started"));
        }
        log.info("Payment intent CREATED: orderId={}, intentId={}, expiresAt={}",
                cmd.getOrderId(), r.intentId(), r.expiresAt());
        return withSuccess(new PaymentIntentCreated(r.intentId(), r.checkoutUrl(), r.expiresAt()));
    }

    /** Deferred: answers now if the outcome is already known, otherwise keeps the token for the webhook. */
    @Transactional
    public void awaitPayment(AwaitPaymentCommand cmd, CommandReplyToken token) {
        PaymentIntentRecord row = intents.lockByOrder(cmd.getOrderId()).orElse(null);
        if (row == null) {
            replies.sendReplies(token, withFailure(new PaymentNotCompleted("UNKNOWN_INTENT", cmd.getIntentId())));
            return;
        }
        switch (row.status()) {
            case CREATED -> {
                intents.attachReplyToken(row.orderId(), token);
                log.info("Awaiting payment: orderId={}, intentId={}", row.orderId(), row.intentId());
            }
            case AUTHORIZED -> replies.sendReplies(token, withSuccess(new PaymentAuthorized(row.intentId())));
            default -> replies.sendReplies(token, withFailure(notCompleted(row.status(), row.lastError())));
        }
    }

    /** The user cancelled before paying. A paid order is past this: the saga's cancel checkpoint handles it. */
    @Transactional
    public void cancelPayment(String orderId, String reason) {
        PaymentIntentRecord row = intents.lockByOrder(orderId).orElse(null);
        if (row == null) {
            intents.insertCancelTombstone(orderId, reason);
            log.info("Payment cancel recorded before the intent exists: orderId={}", orderId);
            return;
        }
        if (row.status() != Status.CREATED) {
            log.info("Payment cancel ignored: orderId={} is {}", orderId, row.status());
            return;
        }
        // Best effort: if the provider can't be reached, a later "authorized" webhook is voided (onAuthorized).
        psp.cancel(row.intentId());
        finish(row, Status.CANCELLED, reason, withFailure(new PaymentNotCompleted("CANCELLED", reason)));
        log.info("Payment CANCELLED by the user: orderId={}, intentId={}", orderId, row.intentId());
    }

    /** Saga compensation: an authorisation that won't be captured. Always succeeds; the void may be owed. */
    @Transactional
    public Message voidPayment(String intentId, String reason) {
        intents.lockByIntent(intentId).ifPresent(row -> {
            if (row.status() == Status.AUTHORIZED) {
                voidOrOwe(row, reason);
            } else {
                log.info("Void ignored: intentId={} is {}", intentId, row.status());
            }
        });
        return withSuccess();
    }

    /** The PAY_NOW pivot. A decline or an unreachable provider fails it: the saga then voids and rolls back. */
    @Transactional
    public Message capture(CaptureBillingCommand cmd) {
        PaymentIntentRecord row = intents.lockByIntent(cmd.getAuthId()).orElse(null);
        if (row == null) {
            return withFailure(new BillingCaptureFailed("UNKNOWN_AUTHORIZATION", cmd.getAuthId()));
        }
        if (row.status() == Status.CAPTURED) {
            return withSuccess(new BillingCaptured(row.intentId()));
        }
        if (row.status() != Status.AUTHORIZED) {
            return withFailure(new BillingCaptureFailed("NOT_AUTHORIZED", "Payment is " + row.status()));
        }
        PspClient.CallResult r = psp.capture(row.intentId());
        if (!r.ok()) {
            String reason = r.outcome() == PspClient.Outcome.REJECTED ? "CAPTURE_DECLINED" : "PSP_UNAVAILABLE";
            ledger.save(entry(row, BillingLedgerEntry.Type.CAPTURE, BillingLedgerEntry.Status.DECLINED));
            log.warn("Capture FAILED: orderId={}, intentId={}, {} {}", row.orderId(), row.intentId(), reason, r.detail());
            return withFailure(new BillingCaptureFailed(reason, r.detail()));
        }
        intents.moveTo(row.orderId(), Status.CAPTURED, null);
        ledger.save(entry(row, BillingLedgerEntry.Type.CAPTURE, BillingLedgerEntry.Status.CAPTURED));
        log.info("CAPTURED: orderId={}, intentId={}, amount={} {}", row.orderId(), row.intentId(),
                row.amount(), row.currency());
        return withSuccess(new BillingCaptured(row.intentId()));
    }

    /** Forward recovery: never fails. If the provider is down the refund is owed and the sweeper retries it. */
    @Transactional
    public Message refund(RefundBillingCommand cmd) {
        intents.lockByIntent(cmd.getAuthId()).ifPresentOrElse(row -> {
            if (row.status() != Status.CAPTURED) {
                log.info("Refund ignored: intentId={} is {}", row.intentId(), row.status());
                return;
            }
            PspClient.CallResult r = psp.refund(row.intentId());
            if (r.ok()) {
                markRefunded(row);
            } else {
                intents.owe(row.orderId(), Status.REFUND_OWED, Instant.now().plus(props.getRetryInterval()), r.detail());
                log.warn("Refund OWED (provider {}): orderId={}, intentId={}", r.outcome(), row.orderId(), row.intentId());
            }
        }, () -> log.warn("Refund for unknown authorization {}", cmd.getAuthId()));
        return withSuccess(new BillingRefunded("ref_" + cmd.getAuthId()));
    }

    // ── webhooks ──────────────────────────────────────────────────────────────

    /** @return false if the event was a duplicate (already applied) */
    @Transactional
    public boolean onWebhook(String eventId, String type, String intentId, String failureReason) {
        if (!intents.recordWebhookEvent(eventId, type)) {
            log.info("Duplicate webhook ignored: eventId={}", eventId);
            return false;
        }
        PaymentIntentRecord row = intents.lockByIntent(intentId).orElse(null);
        if (row == null) {
            log.warn("Webhook for an unknown intent: eventId={}, type={}, intentId={}", eventId, type, intentId);
            return true;
        }
        switch (type) {
            case "payment.authorized" -> onAuthorized(row);
            case "payment.failed"     -> onFailed(row, failureReason);
            default -> log.info("Webhook type not handled: eventId={}, type={}", eventId, type);
        }
        return true;
    }

    private void onAuthorized(PaymentIntentRecord row) {
        switch (row.status()) {
            case CREATED -> {
                ledger.save(entry(row, BillingLedgerEntry.Type.AUTHORIZE, BillingLedgerEntry.Status.AUTHORIZED));
                finish(row, Status.AUTHORIZED, null, withSuccess(new PaymentAuthorized(row.intentId())));
                log.info("Payment AUTHORIZED: orderId={}, intentId={}", row.orderId(), row.intentId());
            }
            // Paid after the order gave up on it: release the customer's money.
            case CANCELLED, EXPIRED, FAILED -> {
                log.warn("Late authorisation for a {} payment, voiding: orderId={}, intentId={}",
                        row.status(), row.orderId(), row.intentId());
                voidOrOwe(row, "authorised after " + row.status());
            }
            default -> log.info("Authorised webhook ignored: orderId={} is {}", row.orderId(), row.status());
        }
    }

    private void onFailed(PaymentIntentRecord row, String failureReason) {
        if (row.status() != Status.CREATED) {
            log.info("Failed webhook ignored: orderId={} is {}", row.orderId(), row.status());
            return;
        }
        ledger.save(entry(row, BillingLedgerEntry.Type.AUTHORIZE, BillingLedgerEntry.Status.DECLINED));
        finish(row, Status.FAILED, failureReason,
                withFailure(new PaymentNotCompleted("DECLINED", failureReason)));
        log.warn("Payment DECLINED: orderId={}, intentId={}, reason={}", row.orderId(), row.intentId(), failureReason);
    }

    // ── sweeper ───────────────────────────────────────────────────────────────

    /** Unpaid intents past their window: cancel at the provider (best effort), then tell the saga. */
    @Transactional
    public int expireDue() {
        var due = intents.lockExpired(props.getBatchSize());
        for (PaymentIntentRecord row : due) {
            psp.cancel(row.intentId());
            finish(row, Status.EXPIRED, "payment window closed",
                    withFailure(new PaymentNotCompleted("EXPIRED", "The payment window closed before payment")));
            log.info("Payment EXPIRED: orderId={}, intentId={}", row.orderId(), row.intentId());
        }
        return due.size();
    }

    @Transactional
    public int retryOwedDue() {
        var due = intents.lockOwedDue(props.getBatchSize());
        for (PaymentIntentRecord row : due) {
            PspClient.CallResult r = row.status() == Status.VOID_OWED ? psp.cancel(row.intentId()) : psp.refund(row.intentId());
            if (r.ok()) {
                if (row.status() == Status.VOID_OWED) {
                    intents.moveTo(row.orderId(), Status.VOIDED, null);
                    log.info("Owed void done: orderId={}, intentId={}", row.orderId(), row.intentId());
                } else {
                    markRefunded(row);
                }
            } else {
                intents.retryLater(row.orderId(), Instant.now().plus(props.getRetryInterval()), r.detail());
                log.warn("Owed {} still failing: orderId={}, {}", row.status(), row.orderId(), r.detail());
            }
        }
        return due.size();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Changes state and, if the saga is waiting on this order, replies in the same transaction. */
    private void finish(PaymentIntentRecord row, Status status, String error, Message reply) {
        if (row.awaitingReply()) {
            replies.sendReplies(row.replyToken(), reply);
        }
        intents.moveTo(row.orderId(), status, error);
    }

    private void voidOrOwe(PaymentIntentRecord row, String reason) {
        PspClient.CallResult r = psp.cancel(row.intentId());
        if (r.ok()) {
            intents.moveTo(row.orderId(), Status.VOIDED, reason);
            log.info("VOIDED: orderId={}, intentId={}, reason={}", row.orderId(), row.intentId(), reason);
        } else {
            intents.owe(row.orderId(), Status.VOID_OWED, Instant.now().plus(props.getRetryInterval()), r.detail());
            log.warn("Void OWED (provider {}): orderId={}, intentId={}", r.outcome(), row.orderId(), row.intentId());
        }
    }

    private void markRefunded(PaymentIntentRecord row) {
        intents.moveTo(row.orderId(), Status.REFUNDED, null);
        ledger.save(entry(row, BillingLedgerEntry.Type.REFUND, BillingLedgerEntry.Status.REFUNDED));
        log.info("REFUNDED: orderId={}, intentId={}", row.orderId(), row.intentId());
    }

    private static PaymentNotCompleted notCompleted(Status status, String detail) {
        String reason = switch (status) {
            case FAILED  -> "DECLINED";
            case EXPIRED -> "EXPIRED";
            default      -> "CANCELLED";
        };
        return new PaymentNotCompleted(reason, detail);
    }

    private static BillingLedgerEntry entry(PaymentIntentRecord row, BillingLedgerEntry.Type type,
                                            BillingLedgerEntry.Status status) {
        return new BillingLedgerEntry("led_" + UUID.randomUUID(), row.orderId(), row.intentId(), type, status,
                row.amount() == null ? 0 : row.amount(), row.currency());
    }
}
