package com.vab.inventory.command;

import com.vab.events.inventory.*;
import com.vab.inventory.domain.LicensePool;
import com.vab.inventory.domain.LicensePoolRepository;
import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.participant.SagaCommandHandlersBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withFailure;
import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;

/**
 * Saga participant — handles commands from PlaceOrderSaga on the
 * "inventoryService" Kafka channel.
 *
 * Each handler is idempotent via the Eventuate Tram received_messages table.
 */
@Component
public class InventoryCommandHandlers {

    private final LicensePoolRepository poolRepo;

    public InventoryCommandHandlers(LicensePoolRepository poolRepo) {
        this.poolRepo = poolRepo;
    }

    public CommandHandlers commandHandlerDefinitions() {
        return SagaCommandHandlersBuilder
                .fromChannel("inventoryService")
                .onMessage(ReserveInventoryCommand.class, this::reserveInventory)
                .onMessage(ReleaseInventoryCommand.class, this::releaseInventory)
                .build();
    }

    // ── Reserve ───────────────────────────────────────────────────────────

    @Transactional
    public Message reserveInventory(CommandMessage<ReserveInventoryCommand> cm) {
        ReserveInventoryCommand cmd = cm.getCommand();

        if (!"LICENSE".equals(cmd.getInventoryType())) {
            // Other types handled in iteration 9
            return withFailure(new InventoryReservationFailed(
                    "UNSUPPORTED_TYPE", "Inventory type " + cmd.getInventoryType() + " not yet implemented"));
        }

        return poolRepo.findByOfferCodeForUpdate(cmd.getResourceRef())
                .map(pool -> {
                    if (!pool.canReserve(cmd.getQuantity())) {
                        return withFailure(new InventoryReservationFailed(
                                "POOL_EXHAUSTED", "No available seats for " + cmd.getResourceRef()));
                    }
                    pool.reserve(cmd.getQuantity());
                    poolRepo.save(pool);
                    String reservationId = UUID.randomUUID().toString();
                    return withSuccess(new InventoryReserved(reservationId));
                })
                .orElseGet(() -> withFailure(new InventoryReservationFailed(
                        "POOL_NOT_FOUND", "License pool not found: " + cmd.getResourceRef())));
    }

    // ── Release (compensation) ────────────────────────────────────────────

    @Transactional
    public Message releaseInventory(CommandMessage<ReleaseInventoryCommand> cm) {
        // For the walking skeleton: look up by reservationId is a simplification.
        // Full impl: store reservation rows with reservationId → offerCode mapping.
        // Returning success regardless to keep compensation idempotent.
        return withSuccess();
    }
}
