package com.vab.events.fulfilment;

import io.eventuate.tram.commands.common.Command;

/**
 * Single fulfilment step of the PlaceOrderSaga (Design/09, Q2(ii)). The saga
 * sends one command and the {@code fulfilment-service} dispatches internally by
 * {@code productType}:
 * <ul>
 *   <li>{@code DIGITAL_SUBSCRIPTION} → provision an OTT entitlement → external ref</li>
 *   <li>{@code SOFTWARE_LICENSE}     → record the key already allocated at reserve
 *       (carried here in {@code activationKey}) → activation key</li>
 *   <li>{@code PHYSICAL_GOOD}        → create a shipment (internal delivery stub) → tracking ref</li>
 * </ul>
 * The saga therefore stays one linear orchestrator; new product types add a
 * branch inside the participant, not in the saga.
 */
public class FulfilOrderCommand implements Command {
    private String orderId;
    private String subscriberId;
    private String offerCode;
    private String productType;
    private String activationKey; // pre-allocated by inventory for SOFTWARE_LICENSE; null otherwise

    public FulfilOrderCommand() {}

    public FulfilOrderCommand(String orderId, String subscriberId, String offerCode,
                              String productType, String activationKey) {
        this.orderId       = orderId;
        this.subscriberId  = subscriberId;
        this.offerCode     = offerCode;
        this.productType   = productType;
        this.activationKey = activationKey;
    }

    public String getOrderId()       { return orderId; }
    public String getSubscriberId()  { return subscriberId; }
    public String getOfferCode()     { return offerCode; }
    public String getProductType()   { return productType; }
    public String getActivationKey() { return activationKey; }
}
