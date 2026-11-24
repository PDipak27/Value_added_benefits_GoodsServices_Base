package com.vab.paymentsim;

import java.time.Instant;

/** One payment intent. Mutated only under the store's per-intent lock ({@code synchronized} on the instance). */
public class PaymentIntent {

    public enum Status { REQUIRES_PAYMENT, AUTHORIZED, FAILED, CANCELED, CAPTURED, VOIDED, REFUNDED }

    private final String  id;
    private final String  clientSecret;
    private final String  orderId;
    private final long    amount;
    private final String  currency;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Status status = Status.REQUIRES_PAYMENT;
    private String failureReason;

    public PaymentIntent(String id, String clientSecret, String orderId, long amount, String currency,
                         Instant createdAt, Instant expiresAt) {
        this.id           = id;
        this.clientSecret = clientSecret;
        this.orderId      = orderId;
        this.amount       = amount;
        this.currency     = currency;
        this.createdAt    = createdAt;
        this.expiresAt    = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return status == Status.REQUIRES_PAYMENT && !now.isBefore(expiresAt);
    }

    public String  getId()            { return id; }
    public String  getClientSecret()  { return clientSecret; }
    public String  getOrderId()       { return orderId; }
    public long    getAmount()        { return amount; }
    public String  getCurrency()      { return currency; }
    public Instant getCreatedAt()     { return createdAt; }
    public Instant getExpiresAt()     { return expiresAt; }
    public Status  getStatus()        { return status; }
    public String  getFailureReason() { return failureReason; }

    public void setStatus(Status status)          { this.status = status; }
    public void setFailureReason(String reason)   { this.failureReason = reason; }
}
