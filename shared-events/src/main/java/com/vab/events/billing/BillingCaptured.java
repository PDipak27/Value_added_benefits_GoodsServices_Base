package com.vab.events.billing;

/** Success reply to {@code CaptureBillingCommand}. */
public class BillingCaptured {
    private String captureId;

    public BillingCaptured() {}

    public BillingCaptured(String captureId) {
        this.captureId = captureId;
    }

    public String getCaptureId() { return captureId; }
}
