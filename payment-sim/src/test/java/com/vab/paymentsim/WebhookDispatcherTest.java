package com.vab.paymentsim;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The webhook signature contract billing verifies: hex(HMAC-SHA256(secret, t + "." + rawBody)).
 * Billing's PspWebhookControllerTest signs with the same recipe.
 */
class WebhookDispatcherTest {

    @Test
    void signature_is_hmac_sha256_over_timestamp_dot_body() throws Exception {
        String body = "{\"id\":\"evt_1\"}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_x".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(("1700000000." + body).getBytes(StandardCharsets.UTF_8)));

        assertThat(WebhookDispatcher.sign("whsec_x", 1700000000L, body)).isEqualTo(expected).hasSize(64);
    }

    @Test
    void any_change_to_body_timestamp_or_secret_changes_the_signature() {
        String sig = WebhookDispatcher.sign("whsec_x", 1L, "{}");

        assertThat(WebhookDispatcher.sign("whsec_x", 2L, "{}")).isNotEqualTo(sig);
        assertThat(WebhookDispatcher.sign("whsec_x", 1L, "{ }")).isNotEqualTo(sig);
        assertThat(WebhookDispatcher.sign("whsec_y", 1L, "{}")).isNotEqualTo(sig);
    }
}
