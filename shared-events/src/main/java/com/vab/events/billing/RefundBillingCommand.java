package com.vab.events.billing;

import io.eventuate.tram.commands.common.Command;

/**
 * billing.Refund.v1 — forward-recovery for a captured charge (DD-26).
 * Capture is the pivot, so this is not a LIFO compensation; it is issued as a
 * forward step when the order is unwound after the pivot (non-transient fulfil
 * failure, or a cancel in the pre-fulfil window) on the way to CANCELLED_REFUNDED.
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
