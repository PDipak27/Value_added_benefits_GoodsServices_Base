package com.vab.events.fulfilment;

import io.eventuate.tram.commands.common.Command;

/**
 * order-service → fulfilment-service (Design/13 §6): the user cancelled a DIGITAL_SUBSCRIPTION order whose
 * provisioning may still be in progress. Fulfilment marks its provisioning task cancelled; its poller then
 * replies {@link OrderProvisioningCancelled} to the waiting saga. A plain Tram command: fulfilment sends no
 * reply to it.
 */
public class CancelProvisioningCommand implements Command {
    private String orderId;
    private String reason;

    public CancelProvisioningCommand() {}

    public CancelProvisioningCommand(String orderId, String reason) {
        this.orderId = orderId;
        this.reason  = reason;
    }

    public String getOrderId() { return orderId; }
    public String getReason()  { return reason; }
}
