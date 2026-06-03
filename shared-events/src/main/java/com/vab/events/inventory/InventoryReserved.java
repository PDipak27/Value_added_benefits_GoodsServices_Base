package com.vab.events.inventory;

public class InventoryReserved {
    private String reservationId;

    public InventoryReserved() {}

    public InventoryReserved(String reservationId) {
        this.reservationId = reservationId;
    }

    public String getReservationId() { return reservationId; }
}
