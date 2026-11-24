package com.vab.events.billing;

/**
 * Failure reply to {@link AwaitPaymentCommand}. {@code reason}: {@code DECLINED} (the provider refused),
 * {@code EXPIRED} (the payment window closed unpaid) or {@code CANCELLED} (the user cancelled).
 */
public class PaymentNotCompleted {
    private String reason;
    private String detail;

    public PaymentNotCompleted() {}

    public PaymentNotCompleted(String reason, String detail) {
        this.reason = reason;
        this.detail = detail;
    }

    public String getReason() { return reason; }
    public String getDetail() { return detail; }
}
