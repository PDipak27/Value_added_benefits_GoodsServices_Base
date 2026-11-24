package com.vab.ott.webhook;

import com.vab.ott.config.OttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

/**
 * Delivers queued webhooks: at least once, signed ({@code Ott-Signature: t=<unix>,v1=<hex HMAC>}), retried with
 * doubling backoff until the partner answers 2xx, then marked FAILED after the attempt budget.
 */
@Component
public class WebhookDelivery {

    public static final String SIGNATURE_HEADER = "Ott-Signature";

    private static final Logger log = LoggerFactory.getLogger(WebhookDelivery.class);
    private static final int BATCH = 20;

    private final WebhookEventRepository events;
    private final OttProperties props;
    private final RestClient http;

    public WebhookDelivery(WebhookEventRepository events, OttProperties props, RestClient.Builder builder) {
        this.events = events;
        this.props  = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString = "${ott.webhook-poll-interval:1s}")
    @Transactional
    public void deliverDue() {
        for (WebhookEvent e : events.lockDue(BATCH)) {
            long t = Instant.now().getEpochSecond();
            try {
                http.post().uri(props.getWebhookUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SIGNATURE_HEADER, "t=" + t + ",v1=" + WebhookOutbox.sign(props.getWebhookSecret(), t, e.getPayload()))
                        .body(e.getPayload())
                        .retrieve()
                        .toBodilessEntity();
                e.delivered();
                log.info("Webhook delivered: id={}, type={}, attempt={}", e.getId(), e.getType(), e.getAttempts());
            } catch (RuntimeException ex) {
                long backoff = Math.min(props.getWebhookMaxBackoff().toMillis(), 1000L << Math.min(e.getAttempts(), 16));
                e.failed(ex.toString(), Instant.now().plusMillis(backoff), props.getWebhookMaxAttempts());
                if (e.getStatus() == WebhookEvent.Status.FAILED) {
                    log.error("Webhook abandoned after {} attempts: id={}, type={}", e.getAttempts(), e.getId(), e.getType());
                } else {
                    log.warn("Webhook delivery failed: id={}, attempt={}, retry in {} ms ({})",
                            e.getId(), e.getAttempts(), backoff, ex.toString());
                }
            }
        }
    }
}
