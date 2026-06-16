package com.vab.billing.domain;

import jakarta.persistence.*;

/**
 * Per-subscriber postpaid account used by BILL_TO_MOBILE. A charge is admitted
 * only when the account is ACTIVE and the order amount fits the credit limit.
 * Unknown subscribers are auto-provisioned with a default ACTIVE/BASIC account.
 */
@Entity
@Table(name = "billing_account", schema = "billing")
public class BillingAccount {

    public enum Status { ACTIVE, SUSPENDED }

    @Id
    @Column(name = "subscriber_id", length = 64)
    private String subscriberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Column(name = "plan_tier", length = 16, nullable = false)
    private String planTier;

    @Column(name = "credit_limit", nullable = false)
    private long creditLimit;

    @Column(name = "current_cycle_balance", nullable = false)
    private long currentCycleBalance;

    protected BillingAccount() {}

    public BillingAccount(String subscriberId, Status status, String planTier, long creditLimit) {
        this.subscriberId        = subscriberId;
        this.status              = status;
        this.planTier            = planTier;
        this.creditLimit         = creditLimit;
        this.currentCycleBalance = 0;
    }

    /** Default account for an unknown subscriber: ACTIVE, BASIC, 1000 limit. */
    public static BillingAccount defaultFor(String subscriberId) {
        return new BillingAccount(subscriberId, Status.ACTIVE, "BASIC", 1000);
    }

    public boolean isActive()           { return status == Status.ACTIVE; }
    public boolean canCharge(long amount) { return isActive() && amount <= creditLimit; }

    public void charge(long amount)  { this.currentCycleBalance += amount; }
    public void release(long amount) { this.currentCycleBalance -= amount; }

    public String  getSubscriberId()        { return subscriberId; }
    public Status  getStatus()              { return status; }
    public String  getPlanTier()            { return planTier; }
    public long    getCreditLimit()         { return creditLimit; }
    public long    getCurrentCycleBalance() { return currentCycleBalance; }
}
