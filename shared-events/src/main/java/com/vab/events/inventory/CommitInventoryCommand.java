package com.vab.events.inventory;

import io.eventuate.tram.commands.common.Command;

/**
 * Commit a temporary reservation (RESERVED → ALLOCATED). Sent by the PAY_NOW
 * flow once payment is authorized: the held units become firm. Type-agnostic —
 * inventory resolves what to commit from the {@code reservationId}.
 */
public class CommitInventoryCommand implements Command {
    private String reservationId;

    public CommitInventoryCommand() {}

    public CommitInventoryCommand(String reservationId) {
        this.reservationId = reservationId;
    }

    public String getReservationId() { return reservationId; }
}
