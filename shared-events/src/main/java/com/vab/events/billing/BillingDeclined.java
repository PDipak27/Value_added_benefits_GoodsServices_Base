package com.vab.events.billing;

/**
 * Failure reply to {@code AuthorizeBillingCommand}. A decline is a
 * <em>permanent</em> business failure — the saga compensates immediately,
 * it does not retry (see Design/04-saga-design.md).
 */
public class BillingDeclined {
    private String reason;
    private String detail;

    public BillingDeclined() {}

    public BillingDeclined(String reason, String detail) {
        this.reason = reason;
        this.detail = detail;
    }

    public String getReason() { return reason; }
    public String getDetail() { return detail; }
}
