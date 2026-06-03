package com.vab.order.saga;

/**
 * Carries all mutable state the Saga orchestrator needs across steps.
 * Persisted by Eventuate Tram Sagas — must be serializable (Jackson).
 */
public class PlaceOrderSagaData {

    private String orderId;
    private String subscriberId;
    private String offerCode;
    private long   amount;
    private String currency;
    private String billingMode;

    // Populated as Saga progresses
    private String reservationId;
    private String authId;
    private String externalRef;

    public PlaceOrderSagaData() {}

    public PlaceOrderSagaData(String orderId, String subscriberId, String offerCode,
                               long amount, String currency, String billingMode) {
        this.orderId      = orderId;
        this.subscriberId = subscriberId;
        this.offerCode    = offerCode;
        this.amount       = amount;
        this.currency     = currency;
        this.billingMode  = billingMode;
    }

    public String getOrderId()       { return orderId; }
    public String getSubscriberId()  { return subscriberId; }
    public String getOfferCode()     { return offerCode; }
    public long   getAmount()        { return amount; }
    public String getCurrency()      { return currency; }
    public String getBillingMode()   { return billingMode; }
    public String getReservationId() { return reservationId; }
    public String getAuthId()        { return authId; }
    public String getExternalRef()   { return externalRef; }

    public void setReservationId(String reservationId) { this.reservationId = reservationId; }
    public void setAuthId(String authId)               { this.authId = authId; }
    public void setExternalRef(String externalRef)     { this.externalRef = externalRef; }
}
