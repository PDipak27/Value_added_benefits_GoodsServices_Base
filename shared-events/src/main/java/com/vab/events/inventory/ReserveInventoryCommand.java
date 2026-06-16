package com.vab.events.inventory;

import io.eventuate.tram.commands.common.Command;

/**
 * Reserve one (or more) units of an offer's inventory.
 *
 * <p>The command is <strong>type-agnostic</strong>: it carries only the
 * {@code offerCode} and {@code quantity}. The inventory service owns the
 * offerCode → inventory-type mapping (LICENSE / PHYSICAL / SLOT) and dispatches
 * to the right handling internally, so the orchestrator never has to know how an
 * offer is physically stocked.
 */
public class ReserveInventoryCommand implements Command {
    private String offerCode;
    private int    quantity;

    public ReserveInventoryCommand() {}

    public ReserveInventoryCommand(String offerCode, int quantity) {
        this.offerCode = offerCode;
        this.quantity  = quantity;
    }

    public String getOfferCode() { return offerCode; }
    public int    getQuantity()  { return quantity; }
}
