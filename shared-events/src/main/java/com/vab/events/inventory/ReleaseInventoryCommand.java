package com.vab.events.inventory;

import io.eventuate.tram.commands.common.Command;

public class ReleaseInventoryCommand implements Command {
    private String reservationId;

    public ReleaseInventoryCommand() {}

    public ReleaseInventoryCommand(String reservationId) {
        this.reservationId = reservationId;
    }

    public String getReservationId() { return reservationId; }
}
