package com.vab.events.fulfilment;

/**
 * Reply: fulfilment succeeded. Carries the {@code productType} and exactly one
 * populated <em>artifact</em> — {@code trackingRef} (PHYSICAL_GOOD),
 * {@code activationKey} (SOFTWARE_LICENSE) or {@code externalRef}
 * (DIGITAL_SUBSCRIPTION) — plus a {@code fulfilmentRef} the cancel compensation
 * can act on.
 */
public class OrderFulfilled {
    private String productType;
    private String fulfilmentRef;  // handle for CancelFulfilmentCommand (shipmentId / entitlement ref / key)
    private String trackingRef;
    private String activationKey;
    private String externalRef;

    public OrderFulfilled() {}

    public OrderFulfilled(String productType, String fulfilmentRef,
                          String trackingRef, String activationKey, String externalRef) {
        this.productType   = productType;
        this.fulfilmentRef = fulfilmentRef;
        this.trackingRef   = trackingRef;
        this.activationKey = activationKey;
        this.externalRef   = externalRef;
    }

    public String getProductType()   { return productType; }
    public String getFulfilmentRef() { return fulfilmentRef; }
    public String getTrackingRef()   { return trackingRef; }
    public String getActivationKey() { return activationKey; }
    public String getExternalRef()   { return externalRef; }
}
