package com.vab.events.billing;

import io.eventuate.tram.commands.common.Command;

/**
 * Saga → billing (PAY_NOW): open a payment intent at the payment provider for this order. Billing replies
 * {@link PaymentIntentCreated} with the checkout URL the user pays on, or {@link PaymentIntentFailed}.
 * Idempotent per order.
 */
public class CreatePaymentIntentCommand implements Command {
    private String orderId;
    private String subscriberId;
    private long   amount;
    private String currency;

    public CreatePaymentIntentCommand() {}

    public CreatePaymentIntentCommand(String orderId, String subscriberId, long amount, String currency) {
        this.orderId      = orderId;
        this.subscriberId = subscriberId;
        this.amount       = amount;
        this.currency     = currency;
    }

    public String getOrderId()      { return orderId; }
    public String getSubscriberId() { return subscriberId; }
    public long   getAmount()       { return amount; }
    public String getCurrency()     { return currency; }
}
