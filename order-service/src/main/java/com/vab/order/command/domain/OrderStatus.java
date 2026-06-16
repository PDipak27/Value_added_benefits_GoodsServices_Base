package com.vab.order.command.domain;

public enum OrderStatus {
    PLACED,
    RESERVING_INVENTORY,
    INVENTORY_RESERVED,
    AUTHORIZING_BILLING,
    BILLING_AUTHORIZED,
    FULFILLING,
    PROVISIONING,
    CONFIRMED,
    COMPLETED,
    FAILED,
    CANCELLED,            // user cancel before the pivot; rolled back, nothing charged (DD-26)
    CANCELLED_REFUNDED,   // unwound after the pivot via forward-recovery: refund/reverse + release (DD-26)
    COMPENSATING,
    COMPENSATION_COMPLETED
}
