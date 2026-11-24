package com.vab.events.billing;

import io.eventuate.tram.commands.common.Command;

/**
 * order-service → billing: the user cancelled a PAY_NOW order that hasn't been paid. Billing cancels the
 * intent (or remembers the cancel if the intent doesn't exist yet) and answers the waiting saga with
 * {@link PaymentNotCompleted}{@code (CANCELLED)}. A plain Tram command: billing sends no reply to it.
 */
public class CancelPaymentCommand implements Command {
    private String orderId;
    private String reason;

    public CancelPaymentCommand() {}

    public CancelPaymentCommand(String orderId, String reason) {
        this.orderId = orderId;
        this.reason  = reason;
    }

    public String getOrderId() { return orderId; }
    public String getReason()  { return reason; }
}
