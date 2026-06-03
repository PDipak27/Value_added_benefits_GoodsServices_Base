package com.vab.events.order;

import io.eventuate.Event;

public class OrderPlaced implements Event {
    private String subscriberId;
    private String offerCode;
    private String priceSnapshotId;
    private long   amount;
    private String currency;
    private String billingMode;
    private String idempotencyKey;

    public OrderPlaced() {}

    public OrderPlaced(String subscriberId, String offerCode, String priceSnapshotId,
                       long amount, String currency, String billingMode, String idempotencyKey) {
        this.subscriberId    = subscriberId;
        this.offerCode       = offerCode;
        this.priceSnapshotId = priceSnapshotId;
        this.amount          = amount;
        this.currency        = currency;
        this.billingMode     = billingMode;
        this.idempotencyKey  = idempotencyKey;
    }

    public String getSubscriberId()    { return subscriberId; }
    public String getOfferCode()       { return offerCode; }
    public String getPriceSnapshotId() { return priceSnapshotId; }
    public long   getAmount()          { return amount; }
    public String getCurrency()        { return currency; }
    public String getBillingMode()     { return billingMode; }
    public String getIdempotencyKey()  { return idempotencyKey; }
}
