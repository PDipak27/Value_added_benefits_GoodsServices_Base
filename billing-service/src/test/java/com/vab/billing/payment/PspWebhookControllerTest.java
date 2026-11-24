package com.vab.billing.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The provider's webhook (Design/15 §5): signature over the raw body, replay window, then apply. */
class PspWebhookControllerTest {

    private static final String SECRET = "whsec_test";
    private static final String BODY = """
            {"id":"evt_1","type":"payment.authorized","created":1,"data":{"intentId":"pi_1","orderId":"ord-1","failureReason":null}}""";

    private final PaymentService payments = mock(PaymentService.class);
    private final PaymentProperties props = new PaymentProperties();
    private final PspWebhookController controller;

    PspWebhookControllerTest() {
        props.setWebhookSecret(SECRET);
        controller = new PspWebhookController(payments, props, new ObjectMapper());
    }

    /** Same recipe as payment-sim's WebhookDispatcher: hex(HMAC-SHA256(secret, t + "." + body)). */
    private static String sign(String secret, long t, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private int post(String header, String body) {
        return controller.receive(header, body.getBytes(StandardCharsets.UTF_8)).getStatusCode().value();
    }

    @Test
    void valid_signature_applies_the_event() throws Exception {
        long t = now();

        assertThat(post("t=" + t + ",v1=" + sign(SECRET, t, BODY), BODY)).isEqualTo(200);

        verify(payments).onWebhook("evt_1", "payment.authorized", "pi_1", null);
    }

    @Test
    void missing_or_wrong_signature_is_401_and_nothing_applied() throws Exception {
        long t = now();

        assertThat(post(null, BODY)).isEqualTo(401);
        assertThat(post("t=" + t + ",v1=" + sign("other-secret", t, BODY), BODY)).isEqualTo(401);
        assertThat(post("t=" + t + ",v1=not-hex", BODY)).isEqualTo(401);
        verifyNoInteractions(payments);
    }

    @Test
    void a_tampered_body_fails_the_signature() throws Exception {
        long t = now();
        String sig = sign(SECRET, t, BODY);

        assertThat(post("t=" + t + ",v1=" + sig, BODY.replace("pi_1", "pi_2"))).isEqualTo(401);
        verifyNoInteractions(payments);
    }

    @Test
    void the_timestamp_is_signed_so_it_cannot_be_refreshed() throws Exception {
        long old = now() - 3600;
        String sig = sign(SECRET, old, BODY);

        assertThat(post("t=" + now() + ",v1=" + sig, BODY)).isEqualTo(401);
        assertThat(post("t=" + old + ",v1=" + sig, BODY)).isEqualTo(400);   // genuine but outside the replay window
        verifyNoInteractions(payments);
    }

    @Test
    void during_secret_rotation_any_matching_v1_is_accepted() throws Exception {
        long t = now();

        assertThat(post("t=" + t + ",v1=" + sign("old-secret", t, BODY) + ",v1=" + sign(SECRET, t, BODY), BODY))
                .isEqualTo(200);
        verify(payments).onWebhook(any(), any(), any(), any());
    }

    @Test
    void signed_but_malformed_event_is_400() throws Exception {
        long t = now();
        String body = "{\"id\":\"evt_9\"}";

        assertThat(post("t=" + t + ",v1=" + sign(SECRET, t, body), body)).isEqualTo(400);
        verifyNoInteractions(payments);
    }
}
