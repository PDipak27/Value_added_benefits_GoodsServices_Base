package com.vab.events.inventory;

/** Reply: a reservation was committed (RESERVED → ALLOCATED). */
public class InventoryCommitted {
    private String reservationId;

    public InventoryCommitted() {}

    public InventoryCommitted(String reservationId) {
        this.reservationId = reservationId;
    }

    public String getReservationId() { return reservationId; }
}
