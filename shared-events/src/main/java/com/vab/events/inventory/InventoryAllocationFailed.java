package com.vab.events.inventory;

/** Failure reply to {@code AllocateInventoryCommand} (OUT_OF_STOCK / POOL_EXHAUSTED / ITEM_NOT_FOUND). */
public class InventoryAllocationFailed {
    private String reason;
    private String detail;

    public InventoryAllocationFailed() {}

    public InventoryAllocationFailed(String reason, String detail) {
        this.reason = reason;
        this.detail = detail;
    }

    public String getReason() { return reason; }
    public String getDetail() { return detail; }
}
