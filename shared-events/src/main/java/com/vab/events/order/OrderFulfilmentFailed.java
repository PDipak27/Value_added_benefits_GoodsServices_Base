package com.vab.events.order;

import com.vab.events.common.EventuateJackson;
import io.eventuate.tram.events.common.DomainEvent;
import java.time.Instant;

/**
 * Published (via the Tram outbox) when an order enters the non-terminal
 * {@code FULFILMENT_FAILED} state — DIGITAL_SUBSCRIPTION provisioning failed at
 * the external {@code ott-service} and the order is parked for admin-driven
 * re-drive (DD-27). Not a terminal event: a later {@code OrderCompleted} (after a
 * successful re-drive or manual override) supersedes it.
 *
 * <p>{@code version} is the aggregate's JPA {@code @Version} at publish time so the
 * read-side projector can discard out-of-order deliveries.
 */
public class OrderFulfilmentFailed implements DomainEvent {
    static { EventuateJackson.register(); }  // Instant field — enable JavaTimeModule

    private Instant failedAt;
    private long    version;
    private String  failedStep;
    private String  reason;

    public OrderFulfilmentFailed() {}

    public OrderFulfilmentFailed(Instant failedAt, long version, String failedStep, String reason) {
        this.failedAt   = failedAt;
        this.version    = version;
        this.failedStep = failedStep;
        this.reason     = reason;
    }

    public Instant getFailedAt()   { return failedAt; }
    public long    getVersion()    { return version; }
    public String  getFailedStep() { return failedStep; }
    public String  getReason()     { return reason; }
}
