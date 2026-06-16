package com.vab.events.billing;

import io.eventuate.tram.commands.common.Command;

/**
 * billing.ReverseLedger.v1 — compensation for {@link AppendToLedgerCommand}.
 * Backs out a next-cycle charge if a later saga step fails.
 */
public class ReverseLedgerCommand implements Command {
    private String ledgerEntryId;
    private String reason;

    public ReverseLedgerCommand() {}

    public ReverseLedgerCommand(String ledgerEntryId, String reason) {
        this.ledgerEntryId = ledgerEntryId;
        this.reason        = reason;
    }

    public String getLedgerEntryId() { return ledgerEntryId; }
    public String getReason()        { return reason; }
}
