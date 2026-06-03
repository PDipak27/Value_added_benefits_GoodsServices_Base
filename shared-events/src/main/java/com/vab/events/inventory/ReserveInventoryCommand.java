package com.vab.events.inventory;

import io.eventuate.tram.commands.common.Command;

public class ReserveInventoryCommand implements Command {
    private String inventoryType;  // PHYSICAL | SLOT | LICENSE
    private String resourceRef;
    private int    quantity;

    public ReserveInventoryCommand() {}

    public ReserveInventoryCommand(String inventoryType, String resourceRef, int quantity) {
        this.inventoryType = inventoryType;
        this.resourceRef   = resourceRef;
        this.quantity      = quantity;
    }

    public String getInventoryType() { return inventoryType; }
    public String getResourceRef()   { return resourceRef; }
    public int    getQuantity()      { return quantity; }
}
