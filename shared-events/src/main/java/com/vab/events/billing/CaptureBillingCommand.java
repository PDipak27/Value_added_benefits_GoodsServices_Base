package com.vab.events.billing;

import io.eventuate.tram.commands.common.Command;

/**
 * billing.Capture.v1 — saga step 4. Charges a previously authorized amount.
 */
public class CaptureBillingCommand implements Command {
    private String authId;
    private long   amount;
    private String currency;

    public CaptureBillingCommand() {}

    public CaptureBillingCommand(String authId, long amount, String currency) {
        this.authId   = authId;
        this.amount   = amount;
        this.currency = currency;
    }

    public String getAuthId()   { return authId; }
    public long   getAmount()   { return amount; }
    public String getCurrency() { return currency; }
}
