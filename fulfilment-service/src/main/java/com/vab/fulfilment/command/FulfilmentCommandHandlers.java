package com.vab.fulfilment.command;

import com.vab.events.common.ProductType;
import com.vab.events.fulfilment.*;
import com.vab.fulfilment.domain.FulfilmentRecord;
import com.vab.fulfilment.domain.FulfilmentRecordRepository;
import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.participant.SagaCommandHandlersBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;

/**
 * Saga participant — handles fulfilment commands on the "fulfilmentService"
 * channel. A single {@link FulfilOrderCommand} is dispatched internally by
 * {@code productType}, so the orchestrator never grows a step per type.
 *
 * <p>Each path is a stub that synthesises a delivery artifact and records a
 * {@link FulfilmentRecord} (the audit row). Fulfil is post-pivot and forward-only
 * (DD-26): a non-transient failure is a success-outcome {@link OrderFulfilmentFailed}
 * reply that drives the saga's forward-recovery, never a rollback. Idempotency comes
 * from the Eventuate Tram {@code received_messages} table.
 */
@Component
public class FulfilmentCommandHandlers {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentCommandHandlers.class);

    private final FulfilmentRecordRepository records;

    public FulfilmentCommandHandlers(FulfilmentRecordRepository records) {
        this.records = records;
    }

    public CommandHandlers commandHandlerDefinitions() {
        return SagaCommandHandlersBuilder
                .fromChannel("fulfilmentService")
                .onMessage(FulfilOrderCommand.class,    this::fulfil)
                .onMessage(CancelFulfilmentCommand.class, this::cancel)
                .build();
    }

    // ── Fulfil ────────────────────────────────────────────────────────────

    @Transactional
    public Message fulfil(CommandMessage<FulfilOrderCommand> cm) {
        FulfilOrderCommand cmd = cm.getCommand();

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
            case DIGITAL_SUBSCRIPTION -> fulfilDigital(cmd);
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
        return withSuccess(new OrderFulfilled(
                ProductType.PHYSICAL_GOOD.name(), fulfilmentRef, trackingRef, null, null));
    }

    /** DIGITAL_SUBSCRIPTION: provision an OTT entitlement → external ref. */
    private Message fulfilDigital(FulfilOrderCommand cmd) {
        String fulfilmentRef = "ent_" + UUID.randomUUID();
        String externalRef   = "OTT-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        records.save(new FulfilmentRecord(fulfilmentRef, cmd.getOrderId(),
                ProductType.DIGITAL_SUBSCRIPTION, null, null, externalRef));
        log.info("Fulfilled DIGITAL_SUBSCRIPTION: orderId={}, entitlement={}, externalRef={}",
                cmd.getOrderId(), fulfilmentRef, externalRef);
        return withSuccess(new OrderFulfilled(
                ProductType.DIGITAL_SUBSCRIPTION.name(), fulfilmentRef, null, null, externalRef));
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
        log.info("Fulfilled SOFTWARE_LICENSE: orderId={}, record={}, activationKey={}",
                cmd.getOrderId(), fulfilmentRef, activationKey);
        return withSuccess(new OrderFulfilled(
                ProductType.SOFTWARE_LICENSE.name(), fulfilmentRef, null, activationKey, null));
    }

    // ── Cancel (compensation) ───────────────────────────────────────────────

    @Transactional
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
