package com.vab.events.billing;

/** Success reply to {@link AwaitPaymentCommand}: the provider authorised the payment; {@code authId} is captured later. */
public class PaymentAuthorized {
    private String authId;

    public PaymentAuthorized() {}

    public PaymentAuthorized(String authId) {
        this.authId = authId;
    }

    public String getAuthId() { return authId; }
}
