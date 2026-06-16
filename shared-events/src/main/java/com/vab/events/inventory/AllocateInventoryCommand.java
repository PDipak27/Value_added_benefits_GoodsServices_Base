package com.vab.events.inventory;

import io.eventuate.tram.commands.common.Command;

/**
 * Allocate inventory firmly in one step (no temporary hold). Sent by the
 * BILL_TO_MOBILE flow, which bypasses payment authorization and takes stock
 * immediately. Type-agnostic — inventory resolves the type from {@code offerCode}.
 */
public class AllocateInventoryCommand implements Command {
    private String offerCode;
    private int    quantity;

    public AllocateInventoryCommand() {}

    public AllocateInventoryCommand(String offerCode, int quantity) {
        this.offerCode = offerCode;
        this.quantity  = quantity;
    }

    public String getOfferCode() { return offerCode; }
    public int    getQuantity()  { return quantity; }
}
