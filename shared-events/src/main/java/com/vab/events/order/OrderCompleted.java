package com.vab.events.order;

import com.vab.events.common.EventuateJackson;
import io.eventuate.tram.events.common.DomainEvent;
import java.time.Instant;

/**
 * Published (via the Tram outbox) when an order is fully completed — fulfilled
 * and (for PAY_NOW) captured. This is the terminal success state, after
 * {@code OrderConfirmed}. Carries the <em>fulfilment artifact</em>: exactly one
 * of {@code trackingRef} / {@code activationKey} / {@code externalRef}, per type.
 */
public class OrderCompleted implements DomainEvent {
    static { EventuateJackson.register(); }  // Instant field — enable JavaTimeModule

    private Instant completedAt;
    private long    version;

    private String  productType;
    private String  trackingRef;    // PHYSICAL_GOOD
    private String  activationKey;  // SOFTWARE_LICENSE
    private String  externalRef;    // DIGITAL_SUBSCRIPTION

    public OrderCompleted() {}

    public OrderCompleted(Instant completedAt, long version, String productType,
                          String trackingRef, String activationKey, String externalRef) {
        this.completedAt   = completedAt;
        this.version       = version;
        this.productType   = productType;
        this.trackingRef   = trackingRef;
        this.activationKey = activationKey;
        this.externalRef   = externalRef;
    }

    public Instant getCompletedAt()   { return completedAt; }
    public long    getVersion()       { return version; }
    public String  getProductType()   { return productType; }
    public String  getTrackingRef()   { return trackingRef; }
    public String  getActivationKey() { return activationKey; }
    public String  getExternalRef()   { return externalRef; }
}
