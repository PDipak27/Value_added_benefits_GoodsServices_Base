package com.vab.events.billing;

import com.vab.events.common.EventuateJackson;

import java.time.Instant;

/** Success reply to {@link CreatePaymentIntentCommand}: where the user pays, and until when. */
public class PaymentIntentCreated {
    static { EventuateJackson.register(); }  // Instant field — enable JavaTimeModule

    private String  intentId;
    private String  checkoutUrl;
    private Instant expiresAt;

    public PaymentIntentCreated() {}

    public PaymentIntentCreated(String intentId, String checkoutUrl, Instant expiresAt) {
        this.intentId    = intentId;
        this.checkoutUrl = checkoutUrl;
        this.expiresAt   = expiresAt;
    }

    public String  getIntentId()    { return intentId; }
    public String  getCheckoutUrl() { return checkoutUrl; }
    public Instant getExpiresAt()   { return expiresAt; }
}
