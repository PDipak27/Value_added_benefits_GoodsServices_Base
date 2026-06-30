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

import java.time.Instant;
import java.time.ZoneOffset;
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
    private final OttProvisioningService     ottProvisioning;

    public FulfilmentCommandHandlers(FulfilmentRecordRepository records,
                                     OttProvisioningService ottProvisioning) {
        this.records         = records;
        this.ottProvisioning = ottProvisioning;
    }

    public CommandHandlers commandHandlerDefinitions() {
        return SagaCommandHandlersBuilder
                .fromChannel("fulfilmentService")
                .onMessage(FulfilOrderCommand.class,    this::fulfil)
                .onMessage(CancelFulfilmentCommand.class, this::cancel)
                .onMessage(RevokeEntitlementCommand.class, this::revokeEntitlement)
                .build();
    }

    /**
     * Admin revoke (Phase 3): DIGITAL_SUBSCRIPTION calls OTT DELETE; SOFTWARE_LICENSE
     * is read-model-only (no external system). Success-outcome reply carrying orderId.
     */
    @Transactional
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
        return withSuccess(new OrderFulfilled(cmd.getOrderId(),
                ProductType.PHYSICAL_GOOD.name(), fulfilmentRef, trackingRef, null, null));
    }

    /**
     * DIGITAL_SUBSCRIPTION: provision an OTT entitlement at the external
     * {@code ott-service} (DD-27). On failure (OTT unavailable after bounded
     * retries, or a 422 rejection) this does NOT forward-recover — it replies
     * {@link OrderProvisioningFailed}, a success-outcome branch reply that parks
     * the order in {@code FULFILMENT_FAILED} for admin-driven re-drive.
     */
    private Message fulfilDigital(FulfilOrderCommand cmd) {
        Validity v = validity(cmd.getTermMonths());
        OttProvisioningService.ProvisionResult r = ottProvisioning.provision(
                cmd.getOrderId(), cmd.getSubscriberId(), cmd.getOfferCode(), v.from(), v.until());
        if (!r.provisioned()) {
            log.warn("Provision PARKED (FULFILMENT_FAILED): orderId={}, reason={}",
                    cmd.getOrderId(), r.reason());
            return withSuccess(new OrderProvisioningFailed(cmd.getOrderId(), r.reason(), r.detail()));
        }
        return withSuccess(new OrderFulfilled(cmd.getOrderId(),
                ProductType.DIGITAL_SUBSCRIPTION.name(), r.fulfilmentRef(), null, null, r.externalRef(),
                v.from(), v.until()));
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
