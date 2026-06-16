package com.vab.billing.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A BILL_TO_MOBILE charge parked on the subscriber's next billing cycle.
 * Created PENDING when the order is placed; flipped to REVERSED if the saga
 * compensates. Reversal is idempotent.
 */
@Entity
@Table(name = "next_cycle_ledger", schema = "billing")
public class NextCycleLedgerEntry {

    public enum Status { PENDING, REVERSED }

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "order_id", length = 64, nullable = false)
    private String orderId;

    @Column(name = "subscriber_id", length = 64, nullable = false)
    private String subscriberId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected NextCycleLedgerEntry() {}

    public NextCycleLedgerEntry(String id, String orderId, String subscriberId,
                                long amount, String currency) {
        this.id           = id;
        this.orderId      = orderId;
        this.subscriberId = subscriberId;
        this.amount       = amount;
        this.currency     = currency;
        this.status       = Status.PENDING;
        this.createdAt    = Instant.now();
    }

    public boolean isReversed() { return status == Status.REVERSED; }
    public void    reverse()    { this.status = Status.REVERSED; }

    public String  getId()           { return id; }
    public String  getOrderId()      { return orderId; }
    public String  getSubscriberId() { return subscriberId; }
    public long    getAmount()       { return amount; }
    public String  getCurrency()     { return currency; }
    public Status  getStatus()       { return status; }
    public Instant getCreatedAt()    { return createdAt; }
}
