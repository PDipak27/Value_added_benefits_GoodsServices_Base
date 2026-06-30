package com.vab.events.fulfilment;

import io.eventuate.tram.commands.common.Command;

/**
 * Admin-initiated revoke of a completed order's entitlement (Phase 3, §B1 / backlog A4).
 * Dispatched on the fulfilment channel: DIGITAL_SUBSCRIPTION calls OTT
 * {@code DELETE /ott/v1/entitlements/{ref}}; SOFTWARE_LICENSE is read-model-only
 * (no external system, no key reclaim in v1).
 */
public class RevokeEntitlementCommand implements Command {
    private String orderId;
    private String productType;
    private String externalRef;   // OTT handle (DIGITAL_SUBSCRIPTION); null otherwise

    public RevokeEntitlementCommand() {}

    public RevokeEntitlementCommand(String orderId, String productType, String externalRef) {
        this.orderId     = orderId;
        this.productType = productType;
        this.externalRef = externalRef;
    }

    public String getOrderId()     { return orderId; }
    public String getProductType() { return productType; }
    public String getExternalRef() { return externalRef; }
}
