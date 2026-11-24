package com.vab.paymentsim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Sends webhooks the way a real provider does: at-least-once, signed, retried with backoff until the
 * merchant answers 2xx. Header {@code Psp-Signature: t=<unix secs>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 */
@Component
public class WebhookDispatcher {

    public static final String SIGNATURE_HEADER = "Psp-Signature";

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final PspProperties props;
    private final ObjectMapper json;
    private final RestClient http;
    private final Queue<Delivery> pending = new ConcurrentLinkedQueue<>();

    public WebhookDispatcher(PspProperties props, ObjectMapper json, RestClient.Builder builder) {
        this.props = props;
        this.json  = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.requestFactory(factory).build();
    }

    /** Queues {@code payment.<type>} for the intent's current state; delivery happens on the next tick. */
    public void publish(String type, PaymentIntent intent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("intentId", intent.getId());
        data.put("orderId", intent.getOrderId());
        data.put("amount", intent.getAmount());
        data.put("currency", intent.getCurrency());
        data.put("status", intent.getStatus().name());
        data.put("failureReason", intent.getFailureReason());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "evt_" + IntentStore.token(12));
        event.put("type", type);
        event.put("created", Instant.now().getEpochSecond());
        event.put("data", data);
        try {
            pending.add(new Delivery((String) event.get("id"), json.writeValueAsString(event)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise webhook", e);
        }
        log.info("Webhook queued: type={}, intentId={}, orderId={}", type, intent.getId(), intent.getOrderId());
    }

    @Scheduled(fixedDelay = 1000)
    void deliverDue() {
        Instant now = Instant.now();
        for (Delivery d : pending) {
            if (d.nextAttemptAt.isAfter(now)) continue;
            if (send(d)) {
                pending.remove(d);
            } else if (++d.attempts >= props.getWebhookMaxAttempts()) {
                pending.remove(d);
                log.error("Webhook abandoned after {} attempts: eventId={}", d.attempts, d.eventId);
            } else {
                long backoff = Math.min(props.getWebhookMaxBackoff().toMillis(), 1000L << Math.min(d.attempts, 16));
                d.nextAttemptAt = now.plusMillis(backoff);
            }
        }
    }

    private boolean send(Delivery d) {
        long t = Instant.now().getEpochSecond();
        try {
            http.post().uri(props.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SIGNATURE_HEADER, "t=" + t + ",v1=" + sign(props.getWebhookSecret(), t, d.body))
                    .body(d.body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Webhook delivered: eventId={}, attempt={}", d.eventId, d.attempts + 1);
            return true;
        } catch (RuntimeException e) {
            log.warn("Webhook delivery failed: eventId={}, attempt={} ({})", d.eventId, d.attempts + 1, e.toString());
            return false;
        }
    }

    static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static final class Delivery {
        final String eventId;
        final String body;
        volatile int attempts;
        volatile Instant nextAttemptAt = Instant.now();

        Delivery(String eventId, String body) {
            this.eventId = eventId;
            this.body    = body;
        }
    }
}
