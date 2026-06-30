package com.vab.events.order;

import com.vab.events.common.EventuateJackson;
import io.eventuate.tram.events.common.DomainEvent;

import java.time.Instant;

/**
 * Published when an admin revokes a completed order's entitlement (Phase 3). The
 * order stays COMPLETED (revoke is not a refund); the projector flips the
 * {@code entitlements_v1} read model to REVOKED, which frees the uniqueness slot.
 */
public class OrderEntitlementRevoked implements DomainEvent {
    static { EventuateJackson.register(); }  // Instant field — enable JavaTimeModule

    private Instant revokedAt;
    private long    version;

    public OrderEntitlementRevoked() {}

    public OrderEntitlementRevoked(Instant revokedAt, long version) {
        this.revokedAt = revokedAt;
        this.version   = version;
    }

    public Instant getRevokedAt() { return revokedAt; }
    public long    getVersion()   { return version; }
}
