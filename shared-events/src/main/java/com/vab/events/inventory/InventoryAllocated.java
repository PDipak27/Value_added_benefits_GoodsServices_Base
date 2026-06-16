package com.vab.events.inventory;

/**
 * Reply: inventory was allocated firmly (BILL_TO_MOBILE one-step path). Mirrors
 * {@link InventoryReserved} but the units are already committed. For
 * {@code SOFTWARE_LICENSE} the allocated {@code activationKey} rides back as the
 * delivery artifact; it is {@code null} for the other types.
 */
public class InventoryAllocated {
    private String reservationId;
    private String productType;
    private String activationKey;

    public InventoryAllocated() {}

    public InventoryAllocated(String reservationId, String productType, String activationKey) {
        this.reservationId = reservationId;
        this.productType   = productType;
        this.activationKey = activationKey;
    }

    public String getReservationId() { return reservationId; }
    public String getProductType()   { return productType; }
    public String getActivationKey() { return activationKey; }
}
