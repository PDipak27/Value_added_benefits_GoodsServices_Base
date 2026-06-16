package com.vab.events.inventory;

import com.vab.events.common.EventuateJackson;
import java.time.Instant;

/**
 * Reply: inventory was reserved (temporary hold, PAY_NOW flow). Carries the
 * {@code reservationId} (used by commit / release), the resolved
 * {@code productType}, and {@code reservedUntil} — the instant the hold expires
 * if not committed (the inventory sweeper auto-releases past it).
 *
 * <p>For {@code SOFTWARE_LICENSE}, reserving allocates a specific key, so the
 * reply also carries the {@code activationKey}; it is {@code null} otherwise.
 */
public class InventoryReserved {
    static { EventuateJackson.register(); }  // Instant field — enable JavaTimeModule

    private String  reservationId;
    private String  productType;
    private String  activationKey;
    private Instant reservedUntil;

    public InventoryReserved() {}

    public InventoryReserved(String reservationId, String productType, String activationKey) {
        this(reservationId, productType, activationKey, null);
    }

    public InventoryReserved(String reservationId, String productType,
                             String activationKey, Instant reservedUntil) {
        this.reservationId = reservationId;
        this.productType   = productType;
        this.activationKey = activationKey;
        this.reservedUntil = reservedUntil;
    }

    public String  getReservationId() { return reservationId; }
    public String  getProductType()   { return productType; }
    public String  getActivationKey() { return activationKey; }
    public Instant getReservedUntil() { return reservedUntil; }
}
