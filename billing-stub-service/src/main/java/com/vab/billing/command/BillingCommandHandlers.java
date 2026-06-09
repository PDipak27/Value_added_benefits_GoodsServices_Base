package com.vab.billing.command;

import com.vab.billing.domain.BillingLedgerEntry;
import com.vab.billing.domain.BillingLedgerEntry.Status;
import com.vab.billing.domain.BillingLedgerEntry.Type;
import com.vab.billing.domain.BillingLedgerRepository;
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
 * Saga participant — handles billing commands on the "billingService" channel.
 *
 * <p>Idempotency is provided by the Eventuate Tram {@code received_messages}
 * table: a redelivered (sagaId, stepId) short-circuits before this code runs.
 *
 * <p>Demo decline rule: any authorization for {@code amount > 999} is declined,
 * driving the saga down its compensation path.
 */
@Component
public class BillingCommandHandlers {

    private static final Logger log = LoggerFactory.getLogger(BillingCommandHandlers.class);

    /** Hardcoded demo threshold (see Design/02 & DD report). */
    private static final long DECLINE_THRESHOLD = 999;

    private final BillingLedgerRepository ledger;

    public BillingCommandHandlers(BillingLedgerRepository ledger) {
        this.ledger = ledger;
    }

    public CommandHandlers commandHandlerDefinitions() {
        return SagaCommandHandlersBuilder
                .fromChannel("billingService")
                .onMessage(AuthorizeBillingCommand.class, this::authorize)
                .onMessage(CaptureBillingCommand.class,   this::capture)
                .onMessage(RefundBillingCommand.class,    this::refund)
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

    // ── Capture (step 4) ────────────────────────────────────────────────────

    @Transactional
    public Message capture(CommandMessage<CaptureBillingCommand> cm) {
        CaptureBillingCommand cmd = cm.getCommand();
        String captureId = "cap_" + UUID.randomUUID();
        ledger.save(new BillingLedgerEntry(
                "led_" + UUID.randomUUID(), null, cmd.getAuthId(),
                Type.CAPTURE, Status.CAPTURED, cmd.getAmount(), cmd.getCurrency()));
        log.info("Billing CAPTURED: authId={}, captureId={}, amount={} {}",
                cmd.getAuthId(), captureId, cmd.getAmount(), cmd.getCurrency());
        return withSuccess(new BillingCaptured(captureId));
    }

    // ── Refund (compensation for capture) ───────────────────────────────────

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
}
