package com.vab.events.billing;

/** Success reply to {@code RefundBillingCommand}. */
public class BillingRefunded {
    private String refundId;

    public BillingRefunded() {}

    public BillingRefunded(String refundId) {
        this.refundId = refundId;
    }

    public String getRefundId() { return refundId; }
}
