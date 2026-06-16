package com.vab.notification.template;

import com.vab.notification.dispatch.NotificationType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTemplatesTest {

    private final NotificationTemplates templates = new NotificationTemplates();

    @Test
    void render_substitutes_placeholders_per_type() {
        String confirmed = templates.render(NotificationType.ORDER_CONFIRMED, Map.of("orderId", "ord-1"));
        assertThat(confirmed).contains("ord-1").contains("confirmed");

        String failed = templates.render(NotificationType.ORDER_FAILED,
                Map.of("orderId", "ord-1", "reason", "OUT_OF_STOCK"));
        assertThat(failed).contains("ord-1").contains("OUT_OF_STOCK");

        String cancelled = templates.render(NotificationType.ORDER_CANCELLED,
                Map.of("orderId", "ord-1", "reason", "USER_CANCEL: before pivot"));
        assertThat(cancelled).contains("ord-1").contains("not been charged");

        String cancelledRefunded = templates.render(NotificationType.ORDER_CANCELLED_REFUNDED,
                Map.of("orderId", "ord-1", "reason", "FULFIL_FAILED: DELIVERY_FAILED"));
        assertThat(cancelledRefunded).contains("ord-1").contains("refunded");
    }

    @Test
    void renderCompletion_names_the_artifact_per_product_type() {
        assertThat(templates.renderCompletion("PHYSICAL_GOOD",
                Map.of("orderId", "ord-1", "trackingRef", "TRK9"))).contains("TRK9");
        assertThat(templates.renderCompletion("SOFTWARE_LICENSE",
                Map.of("orderId", "ord-1", "activationKey", "KEY-9"))).contains("KEY-9");
        assertThat(templates.renderCompletion("DIGITAL_SUBSCRIPTION",
                Map.of("orderId", "ord-1", "externalRef", "OTT-9"))).contains("OTT-9");
    }

    @Test
    void renderCompletion_falls_back_to_default_for_unknown_type() {
        assertThat(templates.renderCompletion("MYSTERY", Map.of("orderId", "ord-1")))
                .contains("complete");
    }

    @Test
    void substitute_renders_null_values_as_empty_string() {
        Map<String, String> vars = new HashMap<>();
        vars.put("orderId", "ord-1");
        vars.put("trackingRef", null); // null artifact must not leak "null" into the body
        assertThat(templates.renderCompletion("PHYSICAL_GOOD", vars))
                .contains("ord-1").doesNotContain("null");
    }
}
