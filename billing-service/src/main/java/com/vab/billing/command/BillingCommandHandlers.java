package com.vab.billing.command;

import com.vab.billing.domain.BillingAccount;
import com.vab.billing.domain.BillingAccountRepository;
import com.vab.billing.domain.NextCycleLedgerEntry;
import com.vab.billing.domain.NextCycleLedgerRepository;
import com.vab.billing.payment.PaymentService;
import com.vab.events.billing.*;
import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.commands.consumer.CommandReplyToken;
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
 * Saga participant on the "billingService" channel. Two flows:
 * <ul>
 *   <li>PAY_NOW, through the external payment provider (Design/15): createPaymentIntent → awaitPayment
 *       (deferred reply: answered when the provider's webhook arrives) → capture[pivot]; void is the
 *       compensation, refund the forward recovery. All of it lives in {@link PaymentService}.</li>
 *   <li>BILL_TO_MOBILE: checkAccountLimit → appendToLedger[pivot]; reverseLedger is forward-recovery.</li>
 * </ul>
 * Idempotency via Tram {@code received_messages}, plus the payment intent's and ledger entry's own state.
 */
@Component
public class BillingCommandHandlers {

    private static final Logger log = LoggerFactory.getLogger(BillingCommandHandlers.class);

    private final PaymentService             payments;
    private final BillingAccountRepository   accounts;
    private final NextCycleLedgerRepository  nextCycle;

    public BillingCommandHandlers(PaymentService payments,
                                  BillingAccountRepository accounts,
                                  NextCycleLedgerRepository nextCycle) {
        this.payments  = payments;
        this.accounts  = accounts;
        this.nextCycle = nextCycle;
    }

    public CommandHandlers commandHandlerDefinitions() {
        return SagaCommandHandlersBuilder
                .fromChannel("billingService")
                // Deferred-reply overload (only on the first registration in Tram 0.26): the reply is sent now,
                // or later when the provider's webhook arrives.
                .onMessage(AwaitPaymentCommand.class,
                        (CommandMessage<AwaitPaymentCommand> cm, CommandReplyToken token) -> awaitPayment(cm, token))
                .onMessage(CreatePaymentIntentCommand.class, this::createPaymentIntent)
                // Plain command from order-service; no reply.
                .onMessage(CancelPaymentCommand.class,
                        (CommandMessage<CancelPaymentCommand> cm) -> cancelPayment(cm))
                .onMessage(VoidPaymentCommand.class,         this::voidPayment)
                .onMessage(CaptureBillingCommand.class,      this::capture)
                .onMessage(RefundBillingCommand.class,       this::refund)
                .onMessage(CheckAccountLimitCommand.class, this::checkAccountLimit)
                .onMessage(AppendToLedgerCommand.class,    this::appendToLedger)
                .onMessage(ReverseLedgerCommand.class,     this::reverseLedger)
                .build();
    }

    // ── PAY_NOW (Design/15): the state machine lives in PaymentService ──────────

    public Message createPaymentIntent(CommandMessage<CreatePaymentIntentCommand> cm) {
        return payments.createIntent(cm.getCommand());
    }

    public void awaitPayment(CommandMessage<AwaitPaymentCommand> cm, CommandReplyToken token) {
        payments.awaitPayment(cm.getCommand(), token);
    }

    public void cancelPayment(CommandMessage<CancelPaymentCommand> cm) {
        payments.cancelPayment(cm.getCommand().getOrderId(), cm.getCommand().getReason());
    }

    public Message voidPayment(CommandMessage<VoidPaymentCommand> cm) {
        return payments.voidPayment(cm.getCommand().getAuthId(), cm.getCommand().getReason());
    }

    public Message capture(CommandMessage<CaptureBillingCommand> cm) {
        return payments.capture(cm.getCommand());
    }

    public Message refund(CommandMessage<RefundBillingCommand> cm) {
        return payments.refund(cm.getCommand());
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
