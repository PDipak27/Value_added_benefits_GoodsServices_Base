package com.vab.ott.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vab.ott.domain.Entitlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Queues partner notifications in the caller's transaction (the outbox), so a state change and its webhook
 * are never one without the other. {@link WebhookDelivery} sends them.
 */
@Component
public class WebhookOutbox {

    public static final String ACTIVATED = "entitlement.activated";
    public static final String REJECTED  = "entitlement.rejected";

    private static final Logger log = LoggerFactory.getLogger(WebhookOutbox.class);

    private final WebhookEventRepository events;
    private final ObjectMapper json;

    public WebhookOutbox(WebhookEventRepository events, ObjectMapper json) {
        this.events = events;
        this.json   = json;
    }

    public WebhookEvent enqueue(String type, Entitlement e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("externalRef", e.getExternalRef());
        data.put("orderId", e.getOrderId());
        data.put("status", e.getStatus().name());
        data.put("validFrom", e.getValidFrom());
        data.put("validUntil", e.getValidUntil());
        data.put("reason", e.getRejectionReason());
        String id = "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("type", type);
        event.put("created", Instant.now().getEpochSecond());
        event.put("data", data);
        try {
            WebhookEvent saved = events.save(new WebhookEvent(id, type, json.writeValueAsString(event)));
            log.info("Webhook queued: id={}, type={}, orderId={}, externalRef={}", id, type, e.getOrderId(), e.getExternalRef());
            return saved;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise webhook", ex);
        }
    }

    /** {@code hex(HMAC-SHA256(secret, t + "." + body))}: what the {@code Ott-Signature} header's {@code v1} carries. */
    public static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }
}
