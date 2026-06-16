package com.vab.inventory.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A persisted reservation row, created on reserve (RESERVED) or one-step allocate
 * (ALLOCATED). It lets commit/release find what to act on from just the
 * {@code reservationId}, carries the {@code licenseKey} for a SOFTWARE_LICENSE,
 * and (when RESERVED) a {@code reservedUntil} expiry the sweeper enforces.
 * {@code status} makes commit and release idempotent.
 */
@Entity
@Table(name = "reservations", schema = "inventory")
public class Reservation {

    public enum Status { RESERVED, ALLOCATED, RELEASED }

    @Id
    @Column(name = "reservation_id", length = 64)
    private String reservationId;

    @Column(name = "offer_code", length = 100, nullable = false)
    private String offerCode;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** The specific key allocated for a SOFTWARE_LICENSE; NULL otherwise. */
    @Column(name = "license_key", length = 100)
    private String licenseKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status = Status.RESERVED;

    /** Expiry of a temporary hold (RESERVED); NULL once committed/allocated. */
    @Column(name = "reserved_until")
    private Instant reservedUntil;

    public Reservation() {}

    /** RESERVED with an expiry (PAY_NOW reserve). */
    public Reservation(String reservationId, String offerCode, int quantity,
                       String licenseKey, Instant reservedUntil) {
        this.reservationId = reservationId;
        this.offerCode     = offerCode;
        this.quantity      = quantity;
        this.licenseKey    = licenseKey;
        this.status        = Status.RESERVED;
        this.reservedUntil = reservedUntil;
    }

    /** Factory for a firm one-step allocation (BILL_TO_MOBILE), no expiry. */
    public static Reservation allocated(String reservationId, String offerCode,
                                        int quantity, String licenseKey) {
        Reservation r = new Reservation();
        r.reservationId = reservationId;
        r.offerCode     = offerCode;
        r.quantity      = quantity;
        r.licenseKey    = licenseKey;
        r.status        = Status.ALLOCATED;
        return r;
    }

    public void markAllocated() { this.status = Status.ALLOCATED; this.reservedUntil = null; }
    public void markReleased()  { this.status = Status.RELEASED; }

    public boolean isReserved()  { return status == Status.RESERVED; }
    public boolean isAllocated() { return status == Status.ALLOCATED; }
    public boolean isReleased()  { return status == Status.RELEASED; }

    public String  getReservationId() { return reservationId; }
    public String  getOfferCode()     { return offerCode; }
    public int     getQuantity()      { return quantity; }
    public String  getLicenseKey()    { return licenseKey; }
    public Status  getStatus()        { return status; }
    public Instant getReservedUntil() { return reservedUntil; }
}
