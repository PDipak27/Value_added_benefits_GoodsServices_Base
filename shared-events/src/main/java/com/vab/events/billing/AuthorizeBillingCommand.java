package com.vab.events.billing;

import io.eventuate.tram.commands.common.Command;

/**
 * billing.Authorize.v1 — saga step 2.
 *
 * Two-phase billing: authorization reserves the amount without charging.
 * Capture (see {@link CaptureBillingCommand}) actually moves the money later.
 */
public class AuthorizeBillingCommand implements Command {
    private String orderId;
    private String subscriberId;
    private long   amount;
    private String currency;
    private String billingMode;

    public AuthorizeBillingCommand() {}

    public AuthorizeBillingCommand(String orderId, String subscriberId,
                                   long amount, String currency, String billingMode) {
        this.orderId      = orderId;
        this.subscriberId = subscriberId;
        this.amount       = amount;
        this.currency     = currency;
        this.billingMode  = billingMode;
    }

    public String getOrderId()      { return orderId; }
    public String getSubscriberId() { return subscriberId; }
    public long   getAmount()       { return amount; }
    public String getCurrency()     { return currency; }
    public String getBillingMode()  { return billingMode; }
}
