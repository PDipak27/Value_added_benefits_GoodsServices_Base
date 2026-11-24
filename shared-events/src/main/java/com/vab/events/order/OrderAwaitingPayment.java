package com.vab.events.order;

import com.vab.events.common.EventuateJackson;
import io.eventuate.tram.events.common.DomainEvent;

import java.time.Instant;

/**
 * Published when a PAY_NOW order has a payment intent: the subscriber pays at {@code checkoutUrl} before
 * {@code expiresAt}. The order is {@code PENDING_PAYMENT} until the payment provider confirms.
 */
public class OrderAwaitingPayment implements DomainEvent {
    static { EventuateJackson.register(); }  // Instant field — enable JavaTimeModule

    private String  checkoutUrl;
    private Instant expiresAt;
    private long    version;

    public OrderAwaitingPayment() {}

    public OrderAwaitingPayment(String checkoutUrl, Instant expiresAt, long version) {
        this.checkoutUrl = checkoutUrl;
        this.expiresAt   = expiresAt;
        this.version     = version;
    }

    public String  getCheckoutUrl() { return checkoutUrl; }
    public Instant getExpiresAt()   { return expiresAt; }
    public long    getVersion()     { return version; }
}
