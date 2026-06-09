package com.vab.events.billing;

import io.eventuate.tram.commands.common.Command;

/**
 * billing.Refund.v1 — compensation for {@link CaptureBillingCommand}.
 * Issued LIFO when a step after capture fails.
 */
public class RefundBillingCommand implements Command {
    private String authId;
    private long   amount;
    private String currency;
    private String reason;

    public RefundBillingCommand() {}

    public RefundBillingCommand(String authId, long amount, String currency, String reason) {
        this.authId   = authId;
        this.amount   = amount;
        this.currency = currency;
        this.reason   = reason;
    }

    public String getAuthId()   { return authId; }
    public long   getAmount()   { return amount; }
    public String getCurrency() { return currency; }
    public String getReason()   { return reason; }
}
