package com.vab.notification.dispatch;

/** Business notification types, each mapped to a template + channel. */
public enum NotificationType {
    ORDER_CONFIRMED,
    ORDER_COMPLETED,
    ORDER_FAILED,
    ORDER_CANCELLED,             // subscriber: cancelled before pivot, nothing charged (DD-26)
    ORDER_CANCELLED_REFUNDED;    // subscriber: unwound after pivot, refunded/reversed (DD-26)

    /**
     * Backoffice/admin alert rather than a subscriber-facing message — routed to the ops recipient.
     * None today: the DD-27 FULFILMENT_FAILED admin alert was retired with the park/re-drive flow
     * (Design/13 §6); provisioning problems surface as metrics/alerts instead.
     */
    public boolean isBackoffice() {
        return false;
    }
}
