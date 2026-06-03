package com.vab.events.inventory;

public class InventoryReservationFailed {
    private String reason;
    private String detail;

    public InventoryReservationFailed() {}

    public InventoryReservationFailed(String reason, String detail) {
        this.reason = reason;
        this.detail = detail;
    }

    public String getReason() { return reason; }
    public String getDetail() { return detail; }
}
