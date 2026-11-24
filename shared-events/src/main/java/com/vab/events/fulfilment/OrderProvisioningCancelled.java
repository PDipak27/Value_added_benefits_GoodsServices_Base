package com.vab.events.fulfilment;

/**
 * Reply to {@link FulfilOrderCommand} (Design/13 §6): provisioning stopped because the user cancelled while
 * it was waiting. Success-outcome reply (post-pivot): the saga forward-recovers (refund/reverse → release →
 * CANCELLED_REFUNDED); it never compensates.
 */
public class OrderProvisioningCancelled {
    private String orderId;
    private String reason;

    public OrderProvisioningCancelled() {}

    public OrderProvisioningCancelled(String orderId, String reason) {
        this.orderId = orderId;
        this.reason  = reason;
    }

    public String getOrderId() { return orderId; }
    public String getReason()  { return reason; }
}
