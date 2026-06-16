package com.vab.order.command.domain;

/**
 * Place-order request, carried from the Command API to the command service.
 *
 * Post-DD-14 this is a plain DTO (no Eventuate {@code Command} interface): the
 * state-stored aggregate is mutated directly, not driven through an ES command
 * processor.
 */
public class PlaceOrderCommand {
    private String subscriberId;
    private String offerCode;
    private String productType;   // client-sent (mobile app builds the order from an offer)
    private String priceSnapshotId;
    private long   amount;
    private String currency;
    private String billingMode;
    private String idempotencyKey;

    public PlaceOrderCommand() {}

    public PlaceOrderCommand(String subscriberId, String offerCode, String productType,
                             String priceSnapshotId, long amount, String currency,
                             String billingMode, String idempotencyKey) {
        this.subscriberId    = subscriberId;
        this.offerCode       = offerCode;
        this.productType     = productType;
        this.priceSnapshotId = priceSnapshotId;
        this.amount          = amount;
        this.currency        = currency;
        this.billingMode     = billingMode;
        this.idempotencyKey  = idempotencyKey;
    }

    public String getSubscriberId()    { return subscriberId; }
    public String getOfferCode()       { return offerCode; }
    public String getProductType()     { return productType; }
    public String getPriceSnapshotId() { return priceSnapshotId; }
    public long   getAmount()          { return amount; }
    public String getCurrency()        { return currency; }
    public String getBillingMode()     { return billingMode; }
    public String getIdempotencyKey()  { return idempotencyKey; }
}
