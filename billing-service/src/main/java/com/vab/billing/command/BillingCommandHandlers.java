package com.vab.billing.command;

import com.vab.billing.domain.BillingAccount;
import com.vab.billing.domain.BillingAccountRepository;
import com.vab.billing.domain.BillingLedgerEntry;
import com.vab.billing.domain.BillingLedgerEntry.Status;
import com.vab.billing.domain.BillingLedgerEntry.Type;
import com.vab.billing.domain.BillingLedgerRepository;
import com.vab.billing.domain.NextCycleLedgerEntry;
import com.vab.billing.domain.NextCycleLedgerRepository;
import com.vab.events.billing.*;
import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.participant.SagaCommandHandlersBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withFailure;
import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;

/**
 * Saga participant on the "billingService" channel. Two flows: PAY_NOW
 * (authorize → capture[pivot]; refund is forward-recovery; auth amount &gt; 999
 * declines, capture amount == 777 hard-declines the pivot) and BILL_TO_MOBILE
 * (checkAccountLimit → appendToLedger[pivot]; reverseLedger is forward-recovery).
 * Idempotency via Tram {@code received_messages}; reversal also via entry status.
 */
@Component
public class BillingCommandHandlers {

    private static final Logger log = LoggerFactory.getLogger(BillingCommandHandlers.class);

    /** Hardcoded PAY_NOW authorize demo threshold (see Design/02 & DD report). */
    private static final long DECLINE_THRESHOLD = 999;

    /** Demo trigger (DD-26): capture amount == 777 hard-declines (e.g. bank refused settlement). */
    private static final long CAPTURE_DECLINE_AMOUNT = 777;

    private final BillingLedgerRepository    ledger;
    private final BillingAccountRepository   accounts;
    private final NextCycleLedgerRepository  nextCycle;

    public BillingCommandHandlers(BillingLedgerRepository ledger,
                                  BillingAccountRepository accounts,
                                  NextCycleLedgerRepository nextCycle) {
        this.ledger    = ledger;
        this.accounts  = accounts;
        this.nextCycle = nextCycle;
    }

    public CommandHandlers commandHandlerDefinitions() {
        return SagaCommandHandlersBuilder
                .fromChannel("billingService")
                .onMessage(AuthorizeBillingCommand.class,  this::authorize)
                .onMessage(CaptureBillingCommand.class,    this::capture)
                .onMessage(RefundBillingCommand.class,     this::refund)
                .onMessage(CheckAccountLimitCommand.class, this::checkAccountLimit)
                .onMessage(AppendToLedgerCommand.class,    this::appendToLedger)
                .onMessage(ReverseLedgerCommand.class,     this::reverseLedger)
                .build();
    }

    // ── Authorize (step 2) ──────────────────────────────────────────────────

    @Transactional
    public Message authorize(CommandMessage<AuthorizeBillingCommand> cm) {
        AuthorizeBillingCommand cmd = cm.getCommand();

        if (cmd.getAmount() > DECLINE_THRESHOLD) {
            ledger.save(new BillingLedgerEntry(
                    "led_" + UUID.randomUUID(), cmd.getOrderId(), null,
                    Type.AUTHORIZE, Status.DECLINED, cmd.getAmount(), cmd.getCurrency()));
            log.warn("Billing DECLINED: orderId={}, amount={} {} exceeds limit {}",
                    cmd.getOrderId(), cmd.getAmount(), cmd.getCurrency(), DECLINE_THRESHOLD);
            return withFailure(new BillingDeclined(
                    "AMOUNT_EXCEEDS_LIMIT",
                    "Amount " + cmd.getAmount() + " " + cmd.getCurrency()
                            + " exceeds demo authorization limit of " + DECLINE_THRESHOLD));
        }

        String authId = "auth_" + UUID.randomUUID();
        ledger.save(new BillingLedgerEntry(
                "led_" + UUID.randomUUID(), cmd.getOrderId(), authId,
                Type.AUTHORIZE, Status.AUTHORIZED, cmd.getAmount(), cmd.getCurrency()));
        log.info("Billing AUTHORIZED: orderId={}, authId={}, amount={} {}",
                cmd.getOrderId(), authId, cmd.getAmount(), cmd.getCurrency());
        return withSuccess(new BillingAuthorized(authId));
    }

    // ── Capture (the PAY_NOW pivot; DD-26) ───────────────────────────────────

    @Transactional
    public Message capture(CommandMessage<CaptureBillingCommand> cm) {
        CaptureBillingCommand cmd = cm.getCommand();

        // Hard decline: the pivot failed to commit. Reply withFailure — nothing was
        // captured, so the saga rolls back the prior holds and ends the order FAILED
        // (no refund needed). Capture is the go/no-go point, not a post-pivot step.
        if (cmd.getAmount() == CAPTURE_DECLINE_AMOUNT) {
            ledger.save(new BillingLedgerEntry(
                    "led_" + UUID.randomUUID(), null, cmd.getAuthId(),
                    Type.CAPTURE, Status.DECLINED, cmd.getAmount(), cmd.getCurrency()));
            log.warn("Billing CAPTURE DECLINED: authId={}, amount={} {}",
                    cmd.getAuthId(), cmd.getAmount(), cmd.getCurrency());
            return withFailure(new BillingCaptureFailed(
                    "CAPTURE_DECLINED",
                    "Settlement refused for amount " + cmd.getAmount() + " " + cmd.getCurrency()));
        }

        String captureId = "cap_" + UUID.randomUUID();
        ledger.save(new BillingLedgerEntry(
                "led_" + UUID.randomUUID(), null, cmd.getAuthId(),
                Type.CAPTURE, Status.CAPTURED, cmd.getAmount(), cmd.getCurrency()));
        log.info("Billing CAPTURED: authId={}, captureId={}, amount={} {}",
                cmd.getAuthId(), captureId, cmd.getAmount(), cmd.getCurrency());
        return withSuccess(new BillingCaptured(captureId));
    }

    // ── Refund (forward-recovery for a settled capture; DD-26) ───────────────

    @Transactional
    public Message refund(CommandMessage<RefundBillingCommand> cm) {
        RefundBillingCommand cmd = cm.getCommand();
        String refundId = "ref_" + UUID.randomUUID();
        ledger.save(new BillingLedgerEntry(
                "led_" + UUID.randomUUID(), null, cmd.getAuthId(),
                Type.REFUND, Status.REFUNDED, cmd.getAmount(), cmd.getCurrency()));
        log.info("Billing REFUNDED: authId={}, refundId={}, amount={} {}, reason={}",
                cmd.getAuthId(), refundId, cmd.getAmount(), cmd.getCurrency(), cmd.getReason());
        return withSuccess(new BillingRefunded(refundId));
    }

    // ── Check account limit (BILL_TO_MOBILE gate) ────────────────────────────

    @Transactional
    public Message checkAccountLimit(CommandMessage<CheckAccountLimitCommand> cm) {
        CheckAccountLimitCommand cmd = cm.getCommand();
        BillingAccount acct = accounts.findByIdForUpdate(cmd.getSubscriberId())
                .orElseGet(() -> accounts.save(BillingAccount.defaultFor(cmd.getSubscriberId())));
        if (!acct.isActive()) {
            log.warn("Account limit FAILED (SUSPENDED): subscriberId={}", cmd.getSubscriberId());
            return withFailure(new AccountLimitExceeded(
                    "ACCOUNT_SUSPENDED", "Account " + cmd.getSubscriberId() + " is not active"));
        }
        if (!acct.canCharge(cmd.getAmount())) {
            log.warn("Account limit FAILED (LIMIT): subscriberId={}, amount={}, limit={}",
                    cmd.getSubscriberId(), cmd.getAmount(), acct.getCreditLimit());
            return withFailure(new AccountLimitExceeded("CREDIT_LIMIT_EXCEEDED",
                    "Amount " + cmd.getAmount() + " " + cmd.getCurrency()
                            + " exceeds credit limit " + acct.getCreditLimit()));
        }
        log.info("Account limit OK: subscriberId={}, amount={} {}, limit={}",
                cmd.getSubscriberId(), cmd.getAmount(), cmd.getCurrency(), acct.getCreditLimit());
        return withSuccess(new AccountLimitOk(cmd.getSubscriberId()));
    }

    // ── Append to next-cycle ledger (BILL_TO_MOBILE charge) ──────────────────

    @Transactional
    public Message appendToLedger(CommandMessage<AppendToLedgerCommand> cm) {
        AppendToLedgerCommand cmd = cm.getCommand();
        String entryId = "ncl_" + UUID.randomUUID();
        nextCycle.save(new NextCycleLedgerEntry(
                entryId, cmd.getOrderId(), cmd.getSubscriberId(), cmd.getAmount(), cmd.getCurrency()));
        accounts.findByIdForUpdate(cmd.getSubscriberId()).ifPresent(acct -> {
            acct.charge(cmd.getAmount());
            accounts.save(acct);
        });
        log.info("Ledger APPENDED: entryId={}, orderId={}, subscriberId={}, amount={} {}",
                entryId, cmd.getOrderId(), cmd.getSubscriberId(), cmd.getAmount(), cmd.getCurrency());
        return withSuccess(new LedgerAppended(entryId));
    }

    // ── Reverse next-cycle entry (forward-recovery for appendToLedger; DD-26) ─

    @Transactional
    public Message reverseLedger(CommandMessage<ReverseLedgerCommand> cm) {
        ReverseLedgerCommand cmd = cm.getCommand();
        String entryId = cmd.getLedgerEntryId();
        NextCycleLedgerEntry entry = nextCycle.findById(entryId).orElse(null);
        if (entry == null) {
            log.info("Ledger reversal no-op (unknown entryId={})", entryId);
            return withSuccess(new LedgerReversed("rev_" + UUID.randomUUID()));
        }
        if (!entry.isReversed()) {
            entry.reverse();
            nextCycle.save(entry);
            accounts.findByIdForUpdate(entry.getSubscriberId()).ifPresent(acct -> {
                acct.release(entry.getAmount());
                accounts.save(acct);
            });
            log.info("Ledger REVERSED: entryId={}, subscriberId={}, amount={}, reason={}",
                    entryId, entry.getSubscriberId(), entry.getAmount(), cmd.getReason());
        } else {
            log.info("Ledger reversal no-op (already reversed): entryId={}", entryId);
        }
        return withSuccess(new LedgerReversed("rev_" + UUID.randomUUID()));
    }
}
