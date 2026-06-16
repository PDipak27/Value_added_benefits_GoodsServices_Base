package com.vab.notification.dispatch;

/** Business notification types, each mapped to a template + channel. */
public enum NotificationType {
    ORDER_CONFIRMED,
    ORDER_COMPLETED,
    ORDER_FAILED,
    ORDER_CANCELLED,             // subscriber: cancelled before pivot, nothing charged (DD-26)
    ORDER_CANCELLED_REFUNDED     // subscriber: unwound after pivot, refunded/reversed (DD-26)
}
