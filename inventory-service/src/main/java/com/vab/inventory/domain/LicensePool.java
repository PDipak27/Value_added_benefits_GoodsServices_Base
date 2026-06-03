package com.vab.inventory.domain;

import jakarta.persistence.*;

/**
 * Simple license pool for digital entitlements (OTT seats, cloud storage).
 * One row per offer code. Available count decremented on reserve, incremented on release.
 */
@Entity
@Table(name = "license_pools", schema = "inventory")
public class LicensePool {

    @Id
    @Column(name = "offer_code", length = 100)
    private String offerCode;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    @Column(name = "reserved_seats", nullable = false)
    private int reservedSeats = 0;

    @Version
    private long version;  // optimistic locking

    public LicensePool() {}

    public LicensePool(String offerCode, int totalSeats) {
        this.offerCode  = offerCode;
        this.totalSeats = totalSeats;
    }

    public boolean canReserve(int qty) {
        return (totalSeats - reservedSeats) >= qty;
    }

    public void reserve(int qty)  { this.reservedSeats += qty; }
    public void release(int qty)  { this.reservedSeats = Math.max(0, this.reservedSeats - qty); }

    public String getOfferCode()     { return offerCode; }
    public int    getTotalSeats()    { return totalSeats; }
    public int    getReservedSeats() { return reservedSeats; }
    public int    getAvailable()     { return totalSeats - reservedSeats; }
}
