package com.vab.events.order;

import io.eventuate.tram.events.common.DomainEvent;
import java.time.Instant;

/**
 * Published (via the Tram outbox) when the saga confirms a fully-fulfilled order.
 * {@code version} is the aggregate's JPA {@code @Version} at publish time.
 */
public class OrderConfirmed implements DomainEvent {
    private Instant confirmedAt;
    private long    version;

    public OrderConfirmed() {}

    public OrderConfirmed(Instant confirmedAt, long version) {
        this.confirmedAt = confirmedAt;
        this.version     = version;
    }

    public Instant getConfirmedAt() { return confirmedAt; }
    public long    getVersion()     { return version; }
}
