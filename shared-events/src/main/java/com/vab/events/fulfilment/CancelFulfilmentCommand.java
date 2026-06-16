package com.vab.events.fulfilment;

import io.eventuate.tram.commands.common.Command;

/**
 * Compensation for the fulfil step. One command for all types; the
 * {@code fulfilment-service} routes the undo internally by {@code productType}:
 * physical → cancel shipment, digital → revoke entitlement, license → (the key
 * is returned to the pool by the inventory release compensation, so this is a
 * no-op record). Idempotent on {@code fulfilmentRef}.
 */
public class CancelFulfilmentCommand implements Command {
    private String orderId;
    private String productType;
    private String fulfilmentRef;

    public CancelFulfilmentCommand() {}

    public CancelFulfilmentCommand(String orderId, String productType, String fulfilmentRef) {
        this.orderId       = orderId;
        this.productType   = productType;
        this.fulfilmentRef = fulfilmentRef;
    }

    public String getOrderId()       { return orderId; }
    public String getProductType()   { return productType; }
    public String getFulfilmentRef() { return fulfilmentRef; }
}
