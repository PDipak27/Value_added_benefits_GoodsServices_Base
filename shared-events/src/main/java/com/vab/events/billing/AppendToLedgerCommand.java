package com.vab.events.billing;

import io.eventuate.tram.commands.common.Command;

/**
 * billing.AppendToLedger.v1 — BILL_TO_MOBILE charge. Appends the order's cost to
 * the subscriber's next-cycle bill (no money moves now; it lands on the mobile
 * invoice). Reversed on compensation by {@link ReverseLedgerCommand}.
 */
public class AppendToLedgerCommand implements Command {
    private String orderId;
    private String subscriberId;
    private long   amount;
    private String currency;

    public AppendToLedgerCommand() {}

    public AppendToLedgerCommand(String orderId, String subscriberId, long amount, String currency) {
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
