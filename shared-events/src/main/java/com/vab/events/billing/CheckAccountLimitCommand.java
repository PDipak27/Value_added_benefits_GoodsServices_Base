package com.vab.events.billing;

import io.eventuate.tram.commands.common.Command;

/**
 * billing.CheckAccountLimit.v1 — BILL_TO_MOBILE pre-check. Verifies the
 * subscriber's account is active and the charge is within their per-order
 * credit limit before stock is allocated and the cost is billed next cycle.
 */
public class CheckAccountLimitCommand implements Command {
    private String subscriberId;
    private long   amount;
    private String currency;

    public CheckAccountLimitCommand() {}

    public CheckAccountLimitCommand(String subscriberId, long amount, String currency) {
        this.subscriberId = subscriberId;
        this.amount       = amount;
        this.currency     = currency;
    }

    public String getSubscriberId() { return subscriberId; }
    public long   getAmount()       { return amount; }
    public String getCurrency()     { return currency; }
}
