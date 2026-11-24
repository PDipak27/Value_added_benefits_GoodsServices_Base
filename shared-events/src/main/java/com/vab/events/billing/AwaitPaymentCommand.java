package com.vab.events.billing;

import io.eventuate.tram.commands.common.Command;

/**
 * Saga → billing (PAY_NOW): reply once the user has paid. Billing holds the reply (deferred) until the
 * provider's webhook arrives, then answers {@link PaymentAuthorized}; a decline, the payment window
 * expiring or a user cancel answers {@link PaymentNotCompleted}.
 */
public class AwaitPaymentCommand implements Command {
    private String orderId;
    private String intentId;

    public AwaitPaymentCommand() {}

    public AwaitPaymentCommand(String orderId, String intentId) {
        this.orderId  = orderId;
        this.intentId = intentId;
    }

    public String getOrderId()  { return orderId; }
    public String getIntentId() { return intentId; }
}
