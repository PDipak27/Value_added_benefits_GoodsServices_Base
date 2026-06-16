package com.vab.inventory.domain;

import jakarta.persistence.*;

/**
 * One individual software-license activation key in a finite pool (Design/09,
 * Q4 — inventory owns the key pool). Reserving a {@code SOFTWARE_LICENSE} offer
 * <em>allocates a specific key</em> (status FREE → ALLOCATED, stamped with the
 * reservationId); the release compensation returns it to the pool (→ FREE).
 *
 * <p>This is richer than the bare {@link InventoryItem} count: reserve must hand
 * back a concrete key string, which becomes the order's delivery artifact.
 */
@Entity
@Table(name = "license_keys", schema = "inventory")
public class LicenseKey {

    public enum Status { FREE, ALLOCATED }

    @Id
    @Column(name = "license_key", length = 100)
    private String licenseKey;

    @Column(name = "offer_code", length = 100, nullable = false)
    private String offerCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status = Status.FREE;

    @Column(name = "reservation_id", length = 64)
    private String reservationId;

    public LicenseKey() {}

    public LicenseKey(String licenseKey, String offerCode) {
        this.licenseKey = licenseKey;
        this.offerCode  = offerCode;
    }

    /** Allocate this key to a reservation. */
    public void allocate(String reservationId) {
        this.status        = Status.ALLOCATED;
        this.reservationId = reservationId;
    }

    /** Return this key to the free pool. */
    public void free() {
        this.status        = Status.FREE;
        this.reservationId = null;
    }

    public String getLicenseKey()    { return licenseKey; }
    public String getOfferCode()     { return offerCode; }
    public Status getStatus()        { return status; }
    public String getReservationId() { return reservationId; }
}
