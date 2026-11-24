package com.vab.fulfilment.command;

import com.vab.events.common.ProductType;
import com.vab.events.fulfilment.*;
import com.vab.fulfilment.command.OttProvisioningService.Attempt;
import com.vab.fulfilment.domain.FulfilmentRecord;
import com.vab.fulfilment.domain.FulfilmentRecordRepository;
import com.vab.fulfilment.provisioning.ProvisioningMetrics;
import com.vab.fulfilment.provisioning.ProvisioningProperties;
import com.vab.fulfilment.provisioning.ProvisioningTaskService;
import com.vab.fulfilment.provisioning.ProvisioningTaskStore;
import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.commands.consumer.CommandReplyProducer;
import io.eventuate.tram.commands.consumer.CommandReplyToken;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.participant.SagaCommandHandlersBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;

/**
 * Saga participant — handles fulfilment commands on the "fulfilmentService"
 * channel. A single {@link FulfilOrderCommand} is dispatched internally by
 * {@code productType}, so the orchestrator never grows a step per type.
 *
 * <p>Fulfil is post-pivot and forward-only (DD-26): every reply is success-outcome, and
 * a failure reply ({@link OrderFulfilmentFailed}, {@link OrderProvisioningCancelled})
 * drives the saga's forward-recovery (refund), never a rollback.
 *
 * <p><b>DIGITAL_SUBSCRIPTION (Design/13 §6).</b> One OTT attempt inline. If OTT is
 * unavailable, the reply is <em>deferred</em>: the saga's reply token goes into a
 * provisioning task, and the {@code ProvisioningPoller} answers later (provisioned,
 * rejected, deadline passed, or cancelled by the user).
 *
 * <p>Each handler runs inside Eventuate Tram's message transaction (dedupe + handler +
 * outbox), so task rows, records and replies commit together. Idempotency comes from the
 * Tram {@code received_messages} table.
 */
@Component
public class FulfilmentCommandHandlers {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentCommandHandlers.class);

    private final FulfilmentRecordRepository records;
    private final OttProvisioningService     ottProvisioning;
    private final ProvisioningTaskStore      tasks;
    private final ProvisioningTaskService    taskService;
    private final ProvisioningProperties     provisioningProps;
    private final ProvisioningMetrics        metrics;
    private final CommandReplyProducer       replies;

    public FulfilmentCommandHandlers(FulfilmentRecordRepository records,
                                     OttProvisioningService ottProvisioning,
                                     ProvisioningTaskStore tasks,
                                     ProvisioningTaskService taskService,
                                     ProvisioningProperties provisioningProps,
                                     ProvisioningMetrics metrics,
                                     CommandReplyProducer replies) {
        this.records           = records;
        this.ottProvisioning   = ottProvisioning;
        this.tasks             = tasks;
        this.taskService       = taskService;
        this.provisioningProps = provisioningProps;
        this.metrics           = metrics;
        this.replies           = replies;
    }

    public CommandHandlers commandHandlerDefinitions() {
        return SagaCommandHandlersBuilder
                .fromChannel("fulfilmentService")
                // Deferred-reply overload: the handler sends its reply itself (now, or later via the poller).
                .onMessage(FulfilOrderCommand.class,
                        (CommandMessage<FulfilOrderCommand> cm, CommandReplyToken token) -> fulfil(cm, token))
                .onMessage(CancelFulfilmentCommand.class, this::cancel)
                .onMessage(RevokeEntitlementCommand.class, this::revokeEntitlement)
                // Plain command from order-service; no reply.
                .onMessage(CancelProvisioningCommand.class,
                        (CommandMessage<CancelProvisioningCommand> cm) -> cancelProvisioning(cm))
                .onMessage(ReleaseEntitlementCommand.class, this::releaseEntitlement)
                .build();
    }

    /**
     * Admin revoke (Phase 3): DIGITAL_SUBSCRIPTION calls OTT DELETE; SOFTWARE_LICENSE
     * is read-model-only (no external system). Success-outcome reply carrying orderId.
     */
    public Message revokeEntitlement(CommandMessage<RevokeEntitlementCommand> cm) {
        RevokeEntitlementCommand cmd = cm.getCommand();
        if ("DIGITAL_SUBSCRIPTION".equals(cmd.getProductType())
                && !ottProvisioning.revoke(cmd.getExternalRef())) {
            log.warn("Entitlement revoke FAILED at OTT: orderId={}, externalRef={}",
                    cmd.getOrderId(), cmd.getExternalRef());
            return withSuccess(new EntitlementRevokeFailed(cmd.getOrderId(), "OTT_REVOKE_FAILED"));
        }
        log.info("Entitlement revoked: orderId={}, productType={}", cmd.getOrderId(), cmd.getProductType());
        return withSuccess(new EntitlementRevoked(cmd.getOrderId()));
    }

    /** Benefit validity window: activation = now; expiry = now + term (null term = perpetual). */
    private record Validity(Instant from, Instant until) {}

    private static Validity validity(Integer termMonths) {
        Instant from = Instant.now();
        return new Validity(from, termMonths == null ? null
                : from.atOffset(ZoneOffset.UTC).plusMonths(termMonths).toInstant());
    }

    // ── Fulfil ────────────────────────────────────────────────────────────

    public void fulfil(CommandMessage<FulfilOrderCommand> cm, CommandReplyToken token) {
        Message reply = fulfilNowOrDefer(cm.getCommand(), token);
        if (reply != null) {
            replies.sendReplies(token, reply);
        }
    }

    /** @return the reply to send now, or {@code null} if the reply is deferred to the provisioning poller. */
    private Message fulfilNowOrDefer(FulfilOrderCommand cmd, CommandReplyToken token) {
        // The user's cancel overtook this command (Design/13 §6): answer cancelled without calling anyone.
        if (tasks.consumeTombstone(cmd.getOrderId())) {
            log.info("Fulfil skipped, order already cancelled: orderId={}", cmd.getOrderId());
            metrics.outcome("cancelled");
            return withSuccess(new OrderProvisioningCancelled(cmd.getOrderId(), "USER_CANCEL: before fulfilment"));
        }

        // Demo trigger (DD-26): an offerCode containing "FAIL" simulates a
        // non-transient delivery failure (route closed, damaged good, ...). It is a
        // SUCCESS-outcome reply (post-pivot — no rollback); the saga forward-recovers.
        if (cmd.getOfferCode() != null && cmd.getOfferCode().toUpperCase().contains("FAIL")) {
            log.warn("Fulfil FAILED (DELIVERY_FAILED demo trigger): orderId={}, offerCode={}",
                    cmd.getOrderId(), cmd.getOfferCode());
            return withSuccess(new OrderFulfilmentFailed(
                    "DELIVERY_FAILED", "Delivery could not be completed for offer " + cmd.getOfferCode()));
        }

        ProductType type;
        try {
            type = ProductType.valueOf(cmd.getProductType());
        } catch (IllegalArgumentException | NullPointerException e) {
            // Non-transient: unknown type can never be fulfilled. Success-outcome
            // branch reply (post-pivot) → forward-recovery, not a rollback.
            log.warn("Fulfil FAILED (UNKNOWN_PRODUCT_TYPE): orderId={}, productType={}",
                    cmd.getOrderId(), cmd.getProductType());
            return withSuccess(new OrderFulfilmentFailed(
                    "UNKNOWN_PRODUCT_TYPE", "Cannot fulfil unknown product type: " + cmd.getProductType()));
        }

        return switch (type) {
            case PHYSICAL_GOOD        -> fulfilPhysical(cmd);
            case DIGITAL_SUBSCRIPTION -> fulfilDigital(cmd, token);
            case SOFTWARE_LICENSE     -> fulfilLicense(cmd);
        };
    }

    /** PHYSICAL_GOOD: create a shipment (internal delivery stub) → tracking ref. */
    private Message fulfilPhysical(FulfilOrderCommand cmd) {
        String fulfilmentRef = "shp_" + UUID.randomUUID();
        String trackingRef   = "TRK" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        records.save(new FulfilmentRecord(fulfilmentRef, cmd.getOrderId(),
                ProductType.PHYSICAL_GOOD, trackingRef, null, null));
        log.info("Fulfilled PHYSICAL_GOOD: orderId={}, shipment={}, trackingRef={}",
                cmd.getOrderId(), fulfilmentRef, trackingRef);
        return withSuccess(new OrderFulfilled(cmd.getOrderId(),
                ProductType.PHYSICAL_GOOD.name(), fulfilmentRef, trackingRef, null, null));
    }

    /**
     * DIGITAL_SUBSCRIPTION: one OTT attempt (circuit breaker + timeouts, no in-call retries).
     * <ul>
     *   <li>accepted (202) → reply deferred: an AWAITING_ACTIVATION task holds the token until OTT's
     *       activation webhook (Design/06 §8), the deadline or a cancel;</li>
     *   <li>already active (a repeat after an uncertain call) → {@link OrderFulfilled} now;</li>
     *   <li>rejected (422) → {@link OrderFulfilmentFailed}{@code (PROVISIONING_REJECTED)} now → refund;</li>
     *   <li>unavailable → reply deferred: a RETRYING provisioning task holds the token (deadline 2 h).</li>
     * </ul>
     */
    private Message fulfilDigital(FulfilOrderCommand cmd, CommandReplyToken token) {
        Attempt a = ottProvisioning.attempt(cmd);
        switch (a.outcome()) {
            case ACCEPTED -> {
                tasks.insertAwaiting(cmd, token, a.externalRef(), Instant.now().plus(provisioningProps.getDeadline()));
                metrics.outcome("accepted");
                log.info("OTT accepted DIGITAL_SUBSCRIPTION, reply held for its activation webhook: orderId={}, externalRef={}",
                        cmd.getOrderId(), a.externalRef());
                return null;
            }
            case PROVISIONED -> {
                String fulfilmentRef = OttProvisioningService.newFulfilmentRef();
                records.save(a.toRecord(fulfilmentRef));
                metrics.outcome("provisioned");
                log.info("Provisioned DIGITAL_SUBSCRIPTION: orderId={}, entitlement={}, externalRef={}",
                        cmd.getOrderId(), fulfilmentRef, a.externalRef());
                return withSuccess(a.toOrderFulfilled(fulfilmentRef));
            }
            case REJECTED -> {
                metrics.outcome("rejected");
                log.warn("Provisioning REJECTED by OTT: orderId={}, {}", cmd.getOrderId(), a.detail());
                return withSuccess(new OrderFulfilmentFailed("PROVISIONING_REJECTED", a.detail()));
            }
            default -> {
                Instant now = Instant.now();
                boolean inserted = tasks.insertRetrying(cmd, token, a.reason(), a.detail(), a.mayHaveProvisioned(),
                        now.plus(provisioningProps.delayAfter(1)), now.plus(provisioningProps.getDeadline()));
                if (!inserted) {
                    // A row already exists for this order: a second fulfil command for the same order.
                    // Fail the message (it gets parked) rather than leave this saga without a reply.
                    throw new IllegalStateException("Provisioning task already exists for order " + cmd.getOrderId());
                }
                metrics.outcome("deferred");
                log.warn("Provisioning DEFERRED (OTT unavailable), reply held until provisioned / deadline / cancel: "
                        + "orderId={}, {}", cmd.getOrderId(), a.detail());
                return null;
            }
        }
    }

    /** SOFTWARE_LICENSE: the key was allocated by inventory at reserve; echo it. */
    private Message fulfilLicense(FulfilOrderCommand cmd) {
        String activationKey = cmd.getActivationKey();
        if (activationKey == null || activationKey.isBlank()) {
            // Non-transient: no key was allocated. Success-outcome branch reply
            // (post-pivot) → forward-recovery, not a rollback.
            log.warn("Fulfil FAILED (NO_ACTIVATION_KEY): orderId={}", cmd.getOrderId());
            return withSuccess(new OrderFulfilmentFailed(
                    "NO_ACTIVATION_KEY", "SOFTWARE_LICENSE fulfilment requires a pre-allocated key"));
        }
        String fulfilmentRef = "lic_" + UUID.randomUUID();
        records.save(new FulfilmentRecord(fulfilmentRef, cmd.getOrderId(),
                ProductType.SOFTWARE_LICENSE, null, activationKey, null));
        Validity v = validity(cmd.getTermMonths());
        log.info("Fulfilled SOFTWARE_LICENSE: orderId={}, record={}, activationKey={}, validUntil={}",
                cmd.getOrderId(), fulfilmentRef, activationKey, v.until());
        return withSuccess(new OrderFulfilled(cmd.getOrderId(),
                ProductType.SOFTWARE_LICENSE.name(), fulfilmentRef, null, activationKey, null,
                v.from(), v.until()));
    }

    // ── User cancel while provisioning (Design/13 §6) ───────────────────────

    /**
     * The user cancelled a digital order after the saga's last checkpoint. Locks the task row, so this waits
     * for a poller that is finishing an attempt; the poller (or the fulfil handler, for a tombstone) sends
     * the cancelled reply. No reply to this command.
     */
    public void cancelProvisioning(CommandMessage<CancelProvisioningCommand> cm) {
        CancelProvisioningCommand cmd = cm.getCommand();
        String orderId = cmd.getOrderId();
        var row = tasks.lock(orderId);
        if (row.isPresent()) {
            if (tasks.requestCancel(orderId, cmd.getReason())) {
                log.info("Cancel recorded on provisioning task: orderId={}", orderId);
            } else {
                log.info("Cancel no-op, provisioning already answered: orderId={}, status={}", orderId, row.get().status());
            }
            return;
        }
        if (records.existsByOrderId(orderId)) {
            // Already fulfilled; the saga's last checkpoint (handleOrderFulfilled) sees the cancel flag.
            log.info("Cancel no-op, order already fulfilled: orderId={}", orderId);
            return;
        }
        tasks.insertCancelTombstone(orderId, cmd.getReason(), Instant.now().plus(provisioningProps.getTombstoneTtl()));
        log.info("Cancel arrived before the fulfil command: tombstone recorded, orderId={}", orderId);
    }

    /**
     * Saga step after a late cancel: the entitlement was provisioned, but the order is being refunded.
     * Always succeeds; if OTT can't be reached the revoke is owed and retried by the poller.
     */
    public Message releaseEntitlement(CommandMessage<ReleaseEntitlementCommand> cm) {
        ReleaseEntitlementCommand cmd = cm.getCommand();
        records.findByOrderId(cmd.getOrderId()).forEach(rec -> {
            rec.cancel();
            records.save(rec);
        });
        if (ottProvisioning.revokeByOrder(cmd.getOrderId())) {
            log.info("Entitlement released after cancel: orderId={}", cmd.getOrderId());
        } else {
            taskService.owesRevoke(cmd.getOrderId());
            log.warn("Entitlement release failed at OTT; revoke owed and retried: orderId={}", cmd.getOrderId());
        }
        return withSuccess();
    }

    // ── Cancel (compensation) ───────────────────────────────────────────────

    public Message cancel(CommandMessage<CancelFulfilmentCommand> cm) {
        CancelFulfilmentCommand cmd = cm.getCommand();
        String fulfilmentRef = cmd.getFulfilmentRef();

        records.findById(fulfilmentRef).ifPresentOrElse(rec -> {
            if (rec.getStatus() == FulfilmentRecord.Status.CANCELLED) {
                log.info("Cancel no-op (already cancelled): fulfilmentRef={}", fulfilmentRef);
                return;
            }
            // Type-specific undo is a stub: physical → cancel shipment, digital →
            // revoke entitlement, license → key is returned by inventory release.
            rec.cancel();
            records.save(rec);
            log.info("Cancelled fulfilment: orderId={}, type={}, fulfilmentRef={}",
                    cmd.getOrderId(), cmd.getProductType(), fulfilmentRef);
        }, () ->
            // Unknown ref: fulfil never succeeded — nothing to undo.
            log.info("Cancel no-op (unknown fulfilmentRef={})", fulfilmentRef)
        );

        return withSuccess();
    }
}
