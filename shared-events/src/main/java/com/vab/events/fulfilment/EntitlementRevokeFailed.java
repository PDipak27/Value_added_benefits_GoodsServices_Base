package com.vab.events.fulfilment;

/**
 * Reply: the entitlement revoke could not be completed (e.g. OTT unreachable).
 * Success-outcome reply carrying {@code orderId}; the order's entitlement stays
 * ACTIVE and the admin can retry.
 */
public class EntitlementRevokeFailed {
    private String orderId;
    private String reason;

    public EntitlementRevokeFailed() {}

    public EntitlementRevokeFailed(String orderId, String reason) {
        this.orderId = orderId;
        this.reason  = reason;
    }

    public String getOrderId() { return orderId; }
    public String getReason()  { return reason; }
}
