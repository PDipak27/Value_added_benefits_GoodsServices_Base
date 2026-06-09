package com.vab.notification.dispatch;

import org.springframework.stereotype.Component;

/**
 * Decides which channel a given notification type goes out on.
 *
 * <p>Stub policy: confirmations are high-signal and pushed to the app; failures
 * fall back to SMS so they reach the user even if the app is uninstalled. A real
 * implementation would consult subscriber channel preferences.
 */
@Component
public class NotificationRouter {

    public Channel routeFor(NotificationType type) {
        return switch (type) {
            case ORDER_CONFIRMED -> Channel.PUSH;
            case ORDER_FAILED    -> Channel.SMS;
        };
    }
}
