package com.vab.events.fulfilment;

/**
 * Reply: the entitlement was revoked (Phase 3). Carries {@code orderId} so the
 * order-service reply consumer correlates without saga state (this is a plain
 * Tram command/reply, mirroring the DD-27 re-drive).
 */
public class EntitlementRevoked {
    private String orderId;

    public EntitlementRevoked() {}

    public EntitlementRevoked(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderId() { return orderId; }
}
