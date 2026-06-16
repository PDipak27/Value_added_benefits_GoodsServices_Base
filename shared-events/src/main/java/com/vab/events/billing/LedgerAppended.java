package com.vab.events.billing;

/** Success reply to {@code AppendToLedgerCommand}; carries the ledger entry id. */
public class LedgerAppended {
    private String ledgerEntryId;

    public LedgerAppended() {}

    public LedgerAppended(String ledgerEntryId) {
        this.ledgerEntryId = ledgerEntryId;
    }

    public String getLedgerEntryId() { return ledgerEntryId; }
}
