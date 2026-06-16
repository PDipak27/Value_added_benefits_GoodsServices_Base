package com.vab.events.order;

import com.vab.events.common.EventuateJackson;
import io.eventuate.tram.events.common.DomainEvent;
import java.time.Instant;

/**
 * Published (via the Tram outbox) when inventory is secured and payment is
 * settled (PAY_NOW: authorized + committed; BILL_TO_MOBILE: allocated + billed
 * next cycle). This is an <em>intermediate</em> state — the delivery artifact is
 * not known yet; it arrives with {@code OrderCompleted} after fulfilment.
 * {@code version} is the aggregate's JPA {@code @Version} at publish time.
 */
public class OrderConfirmed implements DomainEvent {
    static { EventuateJackson.register(); }  // Instant field — enable JavaTimeModule

    private Instant confirmedAt;
    private long    version;
    private String  productType;

    public OrderConfirmed() {}

    public OrderConfirmed(Instant confirmedAt, long version, String productType) {
        this.confirmedAt = confirmedAt;
        this.version     = version;
        this.productType = productType;
    }

    public Instant getConfirmedAt() { return confirmedAt; }
    public long    getVersion()     { return version; }
    public String  getProductType() { return productType; }
}
