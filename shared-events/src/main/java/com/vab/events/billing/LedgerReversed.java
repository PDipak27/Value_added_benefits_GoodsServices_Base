package com.vab.events.billing;

/** Success reply to {@code ReverseLedgerCommand}. */
public class LedgerReversed {
    private String reversalId;

    public LedgerReversed() {}

    public LedgerReversed(String reversalId) {
        this.reversalId = reversalId;
    }

    public String getReversalId() { return reversalId; }
}
