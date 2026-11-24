package com.vab.events.billing;

/**
 * Failure reply to {@link CreatePaymentIntentCommand}. {@code reason}: {@code PSP_UNAVAILABLE} (the provider
 * could not be reached) or {@code CANCELLED} (the user cancelled before the intent existed).
 */
public class PaymentIntentFailed {
    private String reason;
    private String detail;

    public PaymentIntentFailed() {}

    public PaymentIntentFailed(String reason, String detail) {
        this.reason = reason;
        this.detail = detail;
    }

    public String getReason() { return reason; }
    public String getDetail() { return detail; }
}
