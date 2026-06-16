package com.vab.events.order;

import com.vab.events.common.EventuateJackson;
import io.eventuate.tram.events.common.DomainEvent;
import java.time.Instant;

/**
 * Published (via the Tram outbox) when an order is unwound <em>after</em> the
 * pivot via forward-recovery (DD-26): a non-transient fulfilment failure (route
 * closed, damaged good) or a user cancel that lands in the pre-fulfil window.
 * The charge is already settled, so the saga moves forward — refund (PAY_NOW) or
 * reverse-ledger (BILL_TO_MOBILE) then release inventory — and the order ends in
 * terminal {@code CANCELLED_REFUNDED}. {@code version} is the aggregate's JPA
 * {@code @Version} at publish time.
 */
public class OrderCancelledRefunded implements DomainEvent {
    static { EventuateJackson.register(); }  // Instant field — enable JavaTimeModule

    private Instant cancelledAt;
    private long    version;
    private String  reason;

    public OrderCancelledRefunded() {}

    public OrderCancelledRefunded(Instant cancelledAt, long version, String reason) {
        this.cancelledAt = cancelledAt;
        this.version     = version;
        this.reason      = reason;
    }

    public Instant getCancelledAt() { return cancelledAt; }
    public long    getVersion()     { return version; }
    public String  getReason()      { return reason; }
}
