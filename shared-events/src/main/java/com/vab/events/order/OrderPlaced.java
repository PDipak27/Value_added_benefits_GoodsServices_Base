package com.vab.events.order;

import io.eventuate.tram.events.common.DomainEvent;

/**
 * Published (via the Tram transactional outbox) when an order is first placed.
 *
 * Post-DD-14: the Order aggregate is state-stored, not event-sourced. Domain
 * events are no longer the source of truth for aggregate state — they are
 * published to Kafka (through the outbox) for projections and downstream
 * consumers. {@code version} carries the aggregate's JPA {@code @Version} at
 * publish time so the projector can drop out-of-order events.
 */
public class OrderPlaced implements DomainEvent {
    private String subscriberId;
    private String offerCode;
    private String priceSnapshotId;
    private long   amount;
    private String currency;
    private String billingMode;
    private String idempotencyKey;
    private long   version;

    public OrderPlaced() {}

    public OrderPlaced(String subscriberId, String offerCode, String priceSnapshotId,
                       long amount, String currency, String billingMode,
                       String idempotencyKey, long version) {
        this.subscriberId    = subscriberId;
        this.offerCode       = offerCode;
        this.priceSnapshotId = priceSnapshotId;
        this.amount          = amount;
        this.currency        = currency;
        this.billingMode     = billingMode;
        this.idempotencyKey  = idempotencyKey;
        this.version         = version;
    }

    public String getSubscriberId()    { return subscriberId; }
    public String getOfferCode()       { return offerCode; }
    public String getPriceSnapshotId() { return priceSnapshotId; }
    public long   getAmount()          { return amount; }
    public String getCurrency()        { return currency; }
    public String getBillingMode()     { return billingMode; }
    public String getIdempotencyKey()  { return idempotencyKey; }
    public long   getVersion()         { return version; }
}
