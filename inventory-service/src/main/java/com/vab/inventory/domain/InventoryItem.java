package com.vab.inventory.domain;

import com.vab.events.common.ProductType;
import jakarta.persistence.*;

/**
 * A unit of reservable, finite inventory, keyed by {@code offerCode}. Covers all
 * three {@link ProductType}s — {@code PHYSICAL_GOOD} (stock count),
 * {@code SOFTWARE_LICENSE} (key-pool size, mirrored here) and, since the
 * payment-mode redesign, {@code DIGITAL_SUBSCRIPTION} (a finite entitlement count).
 *
 * <p>Two holds are tracked: {@code reserved} (temporary, PAY_NOW) and
 * {@code allocated} (firm). Available = {@code total − reserved − allocated}.
 * Commit moves reserved → allocated; release returns whichever a reservation held.
 */
@Entity
@Table(name = "inventory_items", schema = "inventory")
public class InventoryItem {

    @Id
    @Column(name = "offer_code", length = 100)
    private String offerCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false)
    private ProductType type;

    @Column(name = "total", nullable = false)
    private int total;

    @Column(name = "reserved", nullable = false)
    private int reserved = 0;

    @Column(name = "allocated", nullable = false)
    private int allocated = 0;

    @Version
    private long version;  // optimistic locking

    public InventoryItem() {}

    public InventoryItem(String offerCode, ProductType type, int total) {
        this.offerCode = offerCode;
        this.type      = type;
        this.total     = total;
    }

    public boolean canReserve(int qty) { return getAvailable() >= qty; }

    /** Temporary hold (PAY_NOW reserve). */
    public void reserve(int qty) { this.reserved += qty; }

    /** Release a temporary hold (reserve expiry or pre-commit compensation). */
    public void releaseReserved(int qty) { this.reserved = Math.max(0, this.reserved - qty); }

    /** Promote a temporary hold to a firm one (PAY_NOW commit). */
    public void commit(int qty) {
        this.reserved  = Math.max(0, this.reserved - qty);
        this.allocated += qty;
    }

    /** Firm hold in one step (BILL_TO_MOBILE allocate). */
    public void allocate(int qty) { this.allocated += qty; }

    /** Release a firm hold (post-allocate compensation). */
    public void releaseAllocated(int qty) { this.allocated = Math.max(0, this.allocated - qty); }

    public String      getOfferCode() { return offerCode; }
    public ProductType getType()      { return type; }
    public int         getTotal()     { return total; }
    public int         getReserved()  { return reserved; }
    public int         getAllocated() { return allocated; }
    public int         getAvailable() { return total - reserved - allocated; }
}
