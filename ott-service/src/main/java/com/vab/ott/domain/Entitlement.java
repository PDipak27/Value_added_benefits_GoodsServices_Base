package com.vab.ott.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One provisioned streaming entitlement (DD-27). {@code externalRef} is the
 * handle returned to the caller and surfaced to the subscriber; {@code orderId}
 * is unique so a repeated provision request for the same order is idempotent
 * (returns the existing entitlement rather than creating a second).
 */
@Entity
@Table(name = "entitlements", schema = "ott")
public class Entitlement {

    public enum Status { ACTIVE, REVOKED }

    @Id
    @Column(name = "external_ref", length = 64)
    private String externalRef;

    @Column(name = "order_id", length = 64, nullable = false, unique = true)
    private String orderId;

    @Column(name = "subscriber_id", length = 64, nullable = false)
    private String subscriberId;

    @Column(name = "offer_code", length = 100, nullable = false)
    private String offerCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "provisioned_at", nullable = false)
    private Instant provisionedAt = Instant.now();

    /** Benefit validity window supplied by the caller (null until = perpetual). */
    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Entitlement() {}

    public Entitlement(String externalRef, String orderId, String subscriberId, String offerCode,
                       Instant validFrom, Instant validUntil) {
        this.externalRef   = externalRef;
        this.orderId       = orderId;
        this.subscriberId  = subscriberId;
        this.offerCode     = offerCode;
        this.status        = Status.ACTIVE;
        this.provisionedAt = Instant.now();
        this.validFrom     = validFrom;
        this.validUntil    = validUntil;
    }

    /** Idempotent revoke: ACTIVE → REVOKED, stamping revokedAt. A no-op if already revoked. */
    public void revoke() {
        if (this.status == Status.REVOKED) return;
        this.status    = Status.REVOKED;
        this.revokedAt = Instant.now();
    }

    public String  getExternalRef()   { return externalRef; }
    public String  getOrderId()       { return orderId; }
    public String  getSubscriberId()  { return subscriberId; }
    public String  getOfferCode()     { return offerCode; }
    public Status  getStatus()        { return status; }
    public Instant getProvisionedAt() { return provisionedAt; }
    public Instant getValidFrom()     { return validFrom; }
    public Instant getValidUntil()    { return validUntil; }
    public Instant getRevokedAt()     { return revokedAt; }
}
