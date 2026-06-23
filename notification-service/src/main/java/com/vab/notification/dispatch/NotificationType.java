package com.vab.notification.dispatch;

/** Business notification types, each mapped to a template + channel. */
public enum NotificationType {
    ORDER_CONFIRMED,
    ORDER_COMPLETED,
    ORDER_FAILED,
    ORDER_CANCELLED,             // subscriber: cancelled before pivot, nothing charged (DD-26)
    ORDER_CANCELLED_REFUNDED,    // subscriber: unwound after pivot, refunded/reversed (DD-26)
    ORDER_FULFILMENT_FAILED;     // backoffice: OTT provisioning failing — admin must rectify + re-drive (DD-27)

    /** Backoffice/admin alert rather than a subscriber-facing message — routed to the ops recipient (DD-27). */
    public boolean isBackoffice() {
        return this == ORDER_FULFILMENT_FAILED;
    }
}
