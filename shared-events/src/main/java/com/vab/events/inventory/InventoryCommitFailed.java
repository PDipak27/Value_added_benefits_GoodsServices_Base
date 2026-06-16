package com.vab.events.inventory;

/** Failure reply to {@code CommitInventoryCommand} (e.g. unknown reservation). */
public class InventoryCommitFailed {
    private String reason;
    private String detail;

    public InventoryCommitFailed() {}

    public InventoryCommitFailed(String reason, String detail) {
        this.reason = reason;
        this.detail = detail;
    }

    public String getReason() { return reason; }
    public String getDetail() { return detail; }
}
