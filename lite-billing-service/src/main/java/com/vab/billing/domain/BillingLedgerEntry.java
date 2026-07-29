package com.vab.billing.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One row per billing operation (authorize / capture / refund).
 * Gives the stub an auditable ledger and a place to look up an auth by id.
 */
@Entity
@Table(name = "billing_ledger", schema = "billing")
public class BillingLedgerEntry {

    public enum Type   { AUTHORIZE, CAPTURE, REFUND }
    public enum Status { AUTHORIZED, DECLINED, CAPTURED, REFUNDED }

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "auth_id", length = 64)
    private String authId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16, nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BillingLedgerEntry() {}

    public BillingLedgerEntry(String id, String orderId, String authId,
                              Type type, Status status, long amount, String currency) {
        this.id        = id;
        this.orderId   = orderId;
        this.authId    = authId;
        this.type      = type;
        this.status    = status;
        this.amount    = amount;
        this.currency  = currency;
        this.createdAt = Instant.now();
    }

    public String  getId()        { return id; }
    public String  getOrderId()   { return orderId; }
    public String  getAuthId()    { return authId; }
    public Type    getType()      { return type; }
    public Status  getStatus()    { return status; }
    public long    getAmount()    { return amount; }
    public String  getCurrency()  { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
