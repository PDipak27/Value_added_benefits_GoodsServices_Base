package com.vab.notification.template;

import com.vab.notification.dispatch.NotificationType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Renders the message body for a notification from a small set of built-in
 * templates. Placeholders use {@code {key}} substitution.
 *
 * <p>Confirmation is lean (intermediate milestone, no artifact yet). Completion
 * copy is <em>product-type aware</em> (DD-23): the artifact arrives at fulfilment,
 * so completion names it — a tracking ref for a shipped good, an activation key
 * for a license, an external reference for a digital subscription.
 */
@Component
public class NotificationTemplates {

    private static final String FAILED_TEMPLATE =
            "Sorry, your order {orderId} could not be completed ({reason}). You have not been charged.";

    private static final String CONFIRMED_TEMPLATE =
            "Your order {orderId} is confirmed. We'll let you know once it's ready.";

    private static final String COMPLETED_DEFAULT =
            "Your order {orderId} is complete. Enjoy your benefit!";

    private static final String CANCELLED_TEMPLATE =
            "Your order {orderId} has been cancelled ({reason}). You have not been charged.";

    private static final String CANCELLED_REFUNDED_TEMPLATE =
            "Your order {orderId} has been cancelled ({reason}) and fully refunded. "
                    + "Allow a few days for the amount to reappear on your statement.";

    /** Per-product-type completion copy (keyed by ProductType.name()). */
    private static final Map<String, String> COMPLETED_BY_TYPE = Map.of(
            "PHYSICAL_GOOD",
            "Your order {orderId} is on its way. Track it with {trackingRef}.",
            "SOFTWARE_LICENSE",
            "Your order {orderId} is complete. Your activation key is {activationKey}.",
            "DIGITAL_SUBSCRIPTION",
            "Your order {orderId} is complete. Your subscription is active (ref {externalRef})."
    );

    /** Render a simple (non-artifact) message: confirmation or failure. */
    public String render(NotificationType type, Map<String, String> vars) {
        String body = switch (type) {
            case ORDER_FAILED              -> FAILED_TEMPLATE;
            case ORDER_CONFIRMED           -> CONFIRMED_TEMPLATE;
            case ORDER_CANCELLED           -> CANCELLED_TEMPLATE;
            case ORDER_CANCELLED_REFUNDED  -> CANCELLED_REFUNDED_TEMPLATE;
            default                        -> COMPLETED_DEFAULT;
        };
        return substitute(body, vars);
    }

    /** Render the completion message for the given product type (names the artifact). */
    public String renderCompletion(String productType, Map<String, String> vars) {
        String body = COMPLETED_BY_TYPE.getOrDefault(productType, COMPLETED_DEFAULT);
        return substitute(body, vars);
    }

    private String substitute(String template, Map<String, String> vars) {
        String body = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            body = body.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return body;
    }
}
