package com.vab.order.saga;

import com.vab.events.inventory.InventoryReservationFailed;
import com.vab.events.inventory.InventoryReserved;
import com.vab.events.inventory.ReleaseInventoryCommand;
import com.vab.events.inventory.ReserveInventoryCommand;
import com.vab.order.command.domain.ConfirmOrderCommand;
import com.vab.order.command.domain.FailOrderCommand;
import com.vab.order.command.service.OrderCommandService;
import io.eventuate.tram.sagas.orchestration.SagaDefinition;
import io.eventuate.tram.sagas.simpledsl.CommandEndpoint;
import io.eventuate.tram.sagas.simpledsl.CommandEndpointBuilder;
import io.eventuate.tram.sagas.simpledsl.SimpleSaga;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * PlaceOrderSaga — orchestrates the order fulfilment flow.
 *
 * Walking skeleton: one step (inventory reservation).
 * Iteration 5 adds billing; iteration 6 adds OTT provisioning.
 *
 * Pattern (Eventuate Tram Sagas DSL):
 *   step()
 *     .invokeParticipant(command supplier)          → sends command to participant channel
 *     .onReply(SuccessReply.class, handler)         → updates SagaData on success
 *     .onReply(FailureReply.class, handler)         → marks failure
 *     .withCompensation(compensation command)       → called LIFO if a later step fails
 *   .step()...
 *   .build()
 */
@Component
public class PlaceOrderSaga implements SimpleSaga<PlaceOrderSagaData> {

    // Channel name must match the inventory service's @SagaCommandHandlersBuilder channel
    public static final String INVENTORY_CHANNEL = "inventoryService";

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
                .invokeLocal(this::confirmOrder)
            .build();
    }

    @Override
    public SagaDefinition<PlaceOrderSagaData> getSagaDefinition() {
        return sagaDefinition;
    }

    // ── Step builders ─────────────────────────────────────────────────────

    private ReserveInventoryCommand reserveInventory(PlaceOrderSagaData data) {
        return new ReserveInventoryCommand("LICENSE", data.getOfferCode(), 1);
    }

    private ReleaseInventoryCommand releaseInventory(PlaceOrderSagaData data) {
        return new ReleaseInventoryCommand(data.getReservationId());
    }

    // ── Reply handlers ────────────────────────────────────────────────────

    private void handleInventoryReserved(PlaceOrderSagaData data, InventoryReserved reply) {
        data.setReservationId(reply.getReservationId());
    }

    private void handleInventoryReservationFailed(PlaceOrderSagaData data, InventoryReservationFailed reply) {
        // Saga framework triggers compensation automatically;
        // we update the order aggregate via the local step or an event.
        // For the walking skeleton, failure handling is logged — full impl in iteration 5.
    }

    // ── Local steps ───────────────────────────────────────────────────────

    private void confirmOrder(PlaceOrderSagaData data) {
        orderCommandService.confirmOrder(data.getOrderId());
    }
}
