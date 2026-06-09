package com.vab.notification.template;

import com.vab.notification.dispatch.NotificationType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Renders the message body for a notification type from a small set of
 * built-in templates. Placeholders use {@code {key}} substitution.
 */
@Component
public class NotificationTemplates {

    private static final Map<NotificationType, String> TEMPLATES = Map.of(
            NotificationType.ORDER_CONFIRMED,
            "Your order {orderId} is confirmed. Enjoy your benefit!",
            NotificationType.ORDER_FAILED,
            "Sorry, your order {orderId} could not be completed ({reason}). You have not been charged."
    );

    public String render(NotificationType type, Map<String, String> vars) {
        String body = TEMPLATES.getOrDefault(type, "Update on your order {orderId}.");
        for (Map.Entry<String, String> e : vars.entrySet()) {
            body = body.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return body;
    }
}
