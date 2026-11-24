package com.vab.events.fulfilment;

import io.eventuate.tram.commands.common.Command;

/**
 * Saga → fulfilment (Design/13 §6): a DIGITAL_SUBSCRIPTION was provisioned, but the user's cancel won before
 * the saga finished, so the entitlement must go before the refund. Always answered with a plain success: if
 * OTT is down, fulfilment keeps owing the revoke (and retries it) while the refund goes ahead.
 */
public class ReleaseEntitlementCommand implements Command {
    private String orderId;
    private String externalRef;   // may be null; the revoke is by order

    public ReleaseEntitlementCommand() {}

    public ReleaseEntitlementCommand(String orderId, String externalRef) {
        this.orderId     = orderId;
        this.externalRef = externalRef;
    }

    public String getOrderId()     { return orderId; }
    public String getExternalRef() { return externalRef; }
}
