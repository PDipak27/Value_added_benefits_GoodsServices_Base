package com.vab.fulfilment.ott;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vab.fulfilment.provisioning.ProvisioningTaskService;
import com.vab.fulfilment.provisioning.ProvisioningTaskStore;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** OTT's webhook (Design/06 §8): signature over the raw body, replay window, dedupe, then dispatch by type. */
class OttWebhookControllerTest {

    private static final String SECRET = "whsec_test";

    private final ProvisioningTaskService provisioning = mock(ProvisioningTaskService.class);
    private final ProvisioningTaskStore tasks = mock(ProvisioningTaskStore.class);
    private final OttWebhookController controller =
            new OttWebhookController(provisioning, tasks, new ObjectMapper(), SECRET, Duration.ofMinutes(5));

    private static String body(String id, String type) {
        return """
                {"id":"%s","type":"%s","created":1,"data":{"externalRef":"OTT-1","orderId":"ord-1","status":"ACTIVE",\
                "validFrom":"2026-11-20T10:00:00Z","validUntil":null,"reason":"Plan not available"}}""".formatted(id, type);
    }

    private static String sign(String secret, long t, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private int post(String header, String body) {
        return controller.receive(header, body.getBytes(StandardCharsets.UTF_8)).getStatusCode().value();
    }

    private int signedPost(String body) throws Exception {
        long t = Instant.now().getEpochSecond();
        return post("t=" + t + ",v1=" + sign(SECRET, t, body), body);
    }

    @Test
    void activated_event_is_applied_with_its_data() throws Exception {
        when(tasks.recordWebhookEvent("evt_1", "entitlement.activated")).thenReturn(true);

        assertThat(signedPost(body("evt_1", "entitlement.activated"))).isEqualTo(200);

        verify(provisioning).onActivated("ord-1", "OTT-1", Instant.parse("2026-11-20T10:00:00Z"), null);
    }

    @Test
    void rejected_event_is_applied_with_the_reason() throws Exception {
        when(tasks.recordWebhookEvent("evt_2", "entitlement.rejected")).thenReturn(true);

        signedPost(body("evt_2", "entitlement.rejected"));

        verify(provisioning).onRejected("ord-1", "Plan not available");
    }

    @Test
    void a_duplicate_event_is_acknowledged_but_not_applied_again() throws Exception {
        when(tasks.recordWebhookEvent("evt_1", "entitlement.activated")).thenReturn(false);

        assertThat(signedPost(body("evt_1", "entitlement.activated"))).isEqualTo(200);

        verifyNoInteractions(provisioning);
    }

    @Test
    void missing_wrong_or_tampered_signatures_are_401() throws Exception {
        long t = Instant.now().getEpochSecond();
        String body = body("evt_1", "entitlement.activated");

        assertThat(post(null, body)).isEqualTo(401);
        assertThat(post("t=" + t + ",v1=" + sign("other", t, body), body)).isEqualTo(401);
        assertThat(post("t=" + t + ",v1=" + sign(SECRET, t, body), body.replace("OTT-1", "OTT-2"))).isEqualTo(401);
        verifyNoInteractions(provisioning, tasks);
    }

    @Test
    void an_old_genuine_signature_is_outside_the_replay_window() throws Exception {
        long old = Instant.now().getEpochSecond() - 3600;
        String body = body("evt_1", "entitlement.activated");

        assertThat(post("t=" + old + ",v1=" + sign(SECRET, old, body), body)).isEqualTo(400);
        verifyNoInteractions(provisioning, tasks);
    }

    @Test
    void during_secret_rotation_any_matching_signature_is_accepted() throws Exception {
        when(tasks.recordWebhookEvent(anyString(), anyString())).thenReturn(true);
        long t = Instant.now().getEpochSecond();
        String body = body("evt_3", "entitlement.activated");

        assertThat(post("t=" + t + ",v1=" + sign("old", t, body) + ",v1=" + sign(SECRET, t, body), body)).isEqualTo(200);
        verify(provisioning).onActivated(anyString(), anyString(), any(), any());
    }

    @Test
    void an_unknown_event_type_is_acknowledged_and_ignored() throws Exception {
        when(tasks.recordWebhookEvent(anyString(), anyString())).thenReturn(true);

        assertThat(signedPost(body("evt_4", "entitlement.renamed"))).isEqualTo(200);
        verifyNoInteractions(provisioning);
    }
}
