package com.vab.order.saga;

import com.vab.events.billing.AuthorizeBillingCommand;
import com.vab.events.billing.BillingAuthorized;
import com.vab.events.billing.BillingCaptured;
import com.vab.events.billing.BillingDeclined;
import com.vab.events.billing.CaptureBillingCommand;
import com.vab.events.billing.RefundBillingCommand;
import com.vab.events.inventory.InventoryReservationFailed;
import com.vab.events.inventory.InventoryReserved;
import com.vab.events.inventory.ReleaseInventoryCommand;
import com.vab.events.inventory.ReserveInventoryCommand;
import com.vab.order.command.service.OrderCommandService;
import io.eventuate.tram.sagas.orchestration.SagaDefinition;
import io.eventuate.tram.sagas.simpledsl.CommandEndpoint;
import io.eventuate.tram.sagas.simpledsl.CommandEndpointBuilder;
import io.eventuate.tram.sagas.simpledsl.SimpleSaga;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * PlaceOrderSaga — orchestrates the order fulfilment flow.
 *
 * Steps (LIFO compensation):
 *   1. Reserve inventory      → comp: Release inventory
 *   2. Authorize billing      → comp: none (pre-capture, no money moved)
 *   3. Capture billing        → comp: Refund billing
 *   4. Confirm order (local)
 *
 * OTT provisioning (design step 3, between authorize and capture) lands in a
 * later iteration; until then capture follows authorization directly.
 *
 * Pattern (Eventuate Tram Sagas DSL):
 *   step()
 *     .invokeParticipant(endpoint, commandSupplier)  → sends command to participant channel
 *     .onReply(SuccessReply.class, handler)          → updates SagaData on success
 *     .onReply(FailureReply.class, handler)          → marks failure (triggers compensation)
 *     .withCompensation(endpoint, commandSupplier)   → called LIFO if a later step fails
 *   .step()...
 *   .build()
 */
@Component
public class PlaceOrderSaga implements SimpleSaga<PlaceOrderSagaData> {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderSaga.class);

    // Channel names must match each participant's @SagaCommandHandlersBuilder channel
    public static final String INVENTORY_CHANNEL = "inventoryService";
    public static final String BILLING_CHANNEL   = "billingService";

    private final CommandEndpoint<ReserveInventoryCommand> reserveInventoryEndpoint =
            CommandEndpointBuilder
                    .forCommand(ReserveInventoryCommand.class)
                    .withChannel(INVENTORY_CHANNEL)
                    .withReply(InventoryReserved.class)
                    .withReply(InventoryReservationFailed.class)
                    .build();

    private final CommandEndpoint<ReleaseInventoryCommand> releaseInventoryEndpoint =
            CommandEndpointBuilder
                    .forCommand(ReleaseInventoryCommand.class)
                    .withChannel(INVENTORY_CHANNEL)
                    .build();

    private final CommandEndpoint<AuthorizeBillingCommand> authorizeBillingEndpoint =
            CommandEndpointBuilder
                    .forCommand(AuthorizeBillingCommand.class)
                    .withChannel(BILLING_CHANNEL)
                    .withReply(BillingAuthorized.class)
                    .withReply(BillingDeclined.class)
                    .build();

    private final CommandEndpoint<CaptureBillingCommand> captureBillingEndpoint =
            CommandEndpointBuilder
                    .forCommand(CaptureBillingCommand.class)
                    .withChannel(BILLING_CHANNEL)
                    .withReply(BillingCaptured.class)
                    .build();

    private final CommandEndpoint<RefundBillingCommand> refundBillingEndpoint =
            CommandEndpointBuilder
                    .forCommand(RefundBillingCommand.class)
                    .withChannel(BILLING_CHANNEL)
                    .build();

    private final OrderCommandService orderCommandService;

    private final SagaDefinition<PlaceOrderSagaData> sagaDefinition;

    public PlaceOrderSaga(@Lazy OrderCommandService orderCommandService) {
        this.orderCommandService = orderCommandService;

        this.sagaDefinition = step()
                .invokeParticipant(reserveInventoryEndpoint, this::reserveInventory)
                .onReply(InventoryReserved.class, this::handleInventoryReserved)
                .onReply(InventoryReservationFailed.class, this::handleInventoryReservationFailed)
                .withCompensation(releaseInventoryEndpoint, this::releaseInventory)
            .step()
                .invokeParticipant(authorizeBillingEndpoint, this::authorizeBilling)
                .onReply(BillingAuthorized.class, this::handleBillingAuthorized)
                .onReply(BillingDeclined.class, this::handleBillingDeclined)
                // no compensation: authorization is pre-capture, nothing to undo
            .step()
                .invokeParticipant(captureBillingEndpoint, this::captureBilling)
                .onReply(BillingCaptured.class, this::handleBillingCaptured)
                .withCompensation(refundBillingEndpoint, this::refundBilling)
            .step()
                .invokeLocal(this::confirmOrder)
            .build();
    }

    @Override
    public SagaDefinition<PlaceOrderSagaData> getSagaDefinition() {
        return sagaDefinition;
    }

    // ── Step 1: Inventory ───────────────────────────────────────────────────

    private ReserveInventoryCommand reserveInventory(PlaceOrderSagaData data) {
        log.info("Saga step 1 — reserve inventory: orderId={}, offerCode={}",
                data.getOrderId(), data.getOfferCode());
        return new ReserveInventoryCommand("LICENSE", data.getOfferCode(), 1);
    }

    private ReleaseInventoryCommand releaseInventory(PlaceOrderSagaData data) {
        log.info("Saga compensation — release inventory: orderId={}, reservationId={}",
                data.getOrderId(), data.getReservationId());
        return new ReleaseInventoryCommand(data.getReservationId());
    }

    private void handleInventoryReserved(PlaceOrderSagaData data, InventoryReserved reply) {
        log.info("Saga step 1 OK — inventory reserved: orderId={}, reservationId={}",
                data.getOrderId(), reply.getReservationId());
        data.setReservationId(reply.getReservationId());
    }

    private void handleInventoryReservationFailed(PlaceOrderSagaData data, InventoryReservationFailed reply) {
        // No prior step to compensate; mark the order terminally failed.
        log.warn("Saga step 1 FAILED — inventory: orderId={}, reason={}, detail={}",
                data.getOrderId(), reply.getReason(), reply.getDetail());
        orderCommandService.failOrder(data.getOrderId(), "RESERVE_INVENTORY",
                reply.getReason() + ": " + reply.getDetail());
    }

    // ── Step 2: Authorize billing ───────────────────────────────────────────

    private AuthorizeBillingCommand authorizeBilling(PlaceOrderSagaData data) {
        log.info("Saga step 2 — authorize billing: orderId={}, amount={} {}",
                data.getOrderId(), data.getAmount(), data.getCurrency());
        return new AuthorizeBillingCommand(data.getOrderId(), data.getSubscriberId(),
                data.getAmount(), data.getCurrency(), data.getBillingMode());
    }

    private void handleBillingAuthorized(PlaceOrderSagaData data, BillingAuthorized reply) {
        log.info("Saga step 2 OK — billing authorized: orderId={}, authId={}",
                data.getOrderId(), reply.getAuthId());
        data.setAuthId(reply.getAuthId());
    }

    private void handleBillingDeclined(PlaceOrderSagaData data, BillingDeclined reply) {
        // Permanent decline: framework compensates step 1 (release inventory);
        // we mark the order failed.
        log.warn("Saga step 2 DECLINED — billing: orderId={}, reason={}, detail={}",
                data.getOrderId(), reply.getReason(), reply.getDetail());
        orderCommandService.failOrder(data.getOrderId(), "AUTHORIZE_BILLING",
                reply.getReason() + ": " + reply.getDetail());
    }

    // ── Step 3: Capture billing ─────────────────────────────────────────────

    private CaptureBillingCommand captureBilling(PlaceOrderSagaData data) {
        log.info("Saga step 3 — capture billing: orderId={}, authId={}",
                data.getOrderId(), data.getAuthId());
        return new CaptureBillingCommand(data.getAuthId(), data.getAmount(), data.getCurrency());
    }

    private void handleBillingCaptured(PlaceOrderSagaData data, BillingCaptured reply) {
        log.info("Saga step 3 OK — billing captured: orderId={}, captureId={}",
                data.getOrderId(), reply.getCaptureId());
        data.setCaptureId(reply.getCaptureId());
    }

    private RefundBillingCommand refundBilling(PlaceOrderSagaData data) {
        log.info("Saga compensation — refund billing: orderId={}, authId={}",
                data.getOrderId(), data.getAuthId());
        return new RefundBillingCommand(data.getAuthId(), data.getAmount(),
                data.getCurrency(), "saga compensation");
    }

    // ── Step 4: Confirm (local) ─────────────────────────────────────────────

    private void confirmOrder(PlaceOrderSagaData data) {
        log.info("Saga step 4 — confirm order (local): orderId={}", data.getOrderId());
        orderCommandService.confirmOrder(data.getOrderId());
    }
}
