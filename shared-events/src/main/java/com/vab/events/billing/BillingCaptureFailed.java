package com.vab.events.billing;

/**
 * Hard-decline reply to {@code CaptureBillingCommand} (DD-26).
 *
 * <p>Capture <em>is</em> the pivot. A hard decline means the go/no-go point did
 * not commit, so it is returned as a {@code withFailure} reply: the saga rolls
 * back the prior compensatable steps (release the inventory hold) and the order
 * ends {@code FAILED}. Because capture failed, nothing was charged — there is
 * nothing to refund. This type names the decline reason carried back to the
 * orchestrator; it does not by itself imply any forward action.
 */
public class BillingCaptureFailed {
    private String reason;
    private String detail;

    public BillingCaptureFailed() {}

    public BillingCaptureFailed(String reason, String detail) {
        this.reason = reason;
        this.detail = detail;
    }

    public String getReason() { return reason; }
    public String getDetail() { return detail; }
}
