package com.vab.order.saga;

/**
 * Carries all mutable state the Saga orchestrator needs across steps.
 * Persisted by Eventuate Tram Sagas — must be serializable (Jackson).
 */
public class PlaceOrderSagaData {

    private String orderId;
    private String subscriberId;
    private String offerCode;
    private String productType;   // PHYSICAL_GOOD | DIGITAL_SUBSCRIPTION | SOFTWARE_LICENSE
    private long   amount;
    private String currency;
    private String billingMode;

    // Populated as Saga progresses
    private String reservationId;   // PAY_NOW reserve / BILL_TO_MOBILE allocate
    private String authId;          // PAY_NOW authorize
    private String captureId;       // PAY_NOW capture
    private String ledgerEntryId;   // BILL_TO_MOBILE next-cycle charge

    // Forward-recovery branch (DD-26): set post-pivot when fulfilment fails for a
    // non-transient reason, or a user cancel lands in the pre-fulfil window. Gates
    // the refund/reverse + release steps and the CANCELLED_REFUNDED finalize.
    private boolean forwardRecover;
    private String  cancelReason;

    // Fulfilment outcome (exactly one artifact populated, per productType)
    private String fulfilmentRef;
    private String trackingRef;    // PHYSICAL_GOOD
    private String activationKey;  // SOFTWARE_LICENSE (pre-allocated at reserve)
    private String externalRef;    // DIGITAL_SUBSCRIPTION

    public PlaceOrderSagaData() {}

    public PlaceOrderSagaData(String orderId, String subscriberId, String offerCode,
                               String productType, long amount, String currency, String billingMode) {
        this.orderId      = orderId;
        this.subscriberId = subscriberId;
        this.offerCode    = offerCode;
        this.productType  = productType;
        this.amount       = amount;
        this.currency     = currency;
        this.billingMode  = billingMode;
    }

    public String getOrderId()       { return orderId; }
    public String getSubscriberId()  { return subscriberId; }
    public String getOfferCode()     { return offerCode; }
    public String getProductType()   { return productType; }
    public long   getAmount()        { return amount; }
    public String getCurrency()      { return currency; }
    public String getBillingMode()   { return billingMode; }
    public String getReservationId() { return reservationId; }
    public String getAuthId()        { return authId; }
    public String  getCaptureId()    { return captureId; }
    public boolean isForwardRecover(){ return forwardRecover; }
    public String  getCancelReason() { return cancelReason; }
    public String getLedgerEntryId() { return ledgerEntryId; }
    public String getFulfilmentRef() { return fulfilmentRef; }
    public String getTrackingRef()   { return trackingRef; }
    public String getActivationKey() { return activationKey; }
    public String getExternalRef()   { return externalRef; }

    public void setReservationId(String reservationId) { this.reservationId = reservationId; }
    public void setProductType(String productType)     { this.productType = productType; }
    public void setAuthId(String authId)               { this.authId = authId; }
    public void setCaptureId(String captureId)         { this.captureId = captureId; }
    public void setForwardRecover(boolean forwardRecover) { this.forwardRecover = forwardRecover; }
    public void setCancelReason(String cancelReason)   { this.cancelReason = cancelReason; }
    public void setLedgerEntryId(String ledgerEntryId) { this.ledgerEntryId = ledgerEntryId; }
    public void setFulfilmentRef(String fulfilmentRef) { this.fulfilmentRef = fulfilmentRef; }
    public void setTrackingRef(String trackingRef)     { this.trackingRef = trackingRef; }
    public void setActivationKey(String activationKey) { this.activationKey = activationKey; }
    public void setExternalRef(String externalRef)     { this.externalRef = externalRef; }
}
