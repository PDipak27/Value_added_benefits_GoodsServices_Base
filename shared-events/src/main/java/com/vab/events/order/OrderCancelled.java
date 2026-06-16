package com.vab.events.order;

import com.vab.events.common.EventuateJackson;
import io.eventuate.tram.events.common.DomainEvent;
import java.time.Instant;

/**
 * Published (via the Tram outbox) when a user-initiated cancel arrives
 * <em>before</em> the pivot (the charge step) and the saga rolls back (DD-26).
 * Inventory holds are released by compensation; nothing was charged, so there is
 * nothing to refund — the order ends in terminal {@code CANCELLED}. Contrast
 * {@code OrderCancelledRefunded}, used once the pivot has committed.
 * {@code version} is the aggregate's JPA {@code @Version} at publish time.
 */
public class OrderCancelled implements DomainEvent {
    static { EventuateJackson.register(); }  // Instant field — enable JavaTimeModule

    private Instant cancelledAt;
    private long    version;
    private String  reason;

    public OrderCancelled() {}

    public OrderCancelled(Instant cancelledAt, long version, String reason) {
        this.cancelledAt = cancelledAt;
        this.version     = version;
        this.reason      = reason;
    }

    public Instant getCancelledAt() { return cancelledAt; }
    public long    getVersion()     { return version; }
    public String  getReason()      { return reason; }
}
