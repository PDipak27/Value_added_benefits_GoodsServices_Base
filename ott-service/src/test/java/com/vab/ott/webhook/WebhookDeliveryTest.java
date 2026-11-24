package com.vab.ott.webhook;

import com.sun.net.httpserver.HttpServer;
import com.vab.ott.config.OttProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Webhook delivery against a real local HTTP endpoint: signed POST, backoff on failure, FAILED after the budget. */
class WebhookDeliveryTest {

    private HttpServer partner;
    private final List<String[]> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final WebhookEventRepository repo = mock(WebhookEventRepository.class);
    private final OttProperties props = new OttProperties();
    private WebhookDelivery delivery;

    @BeforeEach
    void start() throws Exception {
        partner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        partner.createContext("/webhooks/ott", ex -> {
            received.add(new String[]{ex.getRequestHeaders().getFirst(WebhookDelivery.SIGNATURE_HEADER),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)});
            ex.sendResponseHeaders(failuresLeft.getAndDecrement() > 0 ? 500 : 200, -1);
            ex.close();
        });
        partner.start();
        props.setWebhookUrl("http://127.0.0.1:" + partner.getAddress().getPort() + "/webhooks/ott");
        props.setWebhookSecret("whsec_test");
        delivery = new WebhookDelivery(repo, props, RestClient.builder());
    }

    @AfterEach
    void stop() {
        partner.stop(0);
    }

    private WebhookEvent due() {
        WebhookEvent e = new WebhookEvent("evt_1", WebhookOutbox.ACTIVATED, "{\"id\":\"evt_1\",\"data\":{\"orderId\":\"ord-1\"}}");
        when(repo.lockDue(anyInt())).thenReturn(List.of(e));
        return e;
    }

    @Test
    void delivers_a_signed_event_and_marks_it_delivered() {
        WebhookEvent e = due();

        delivery.deliverDue();

        assertThat(e.getStatus()).isEqualTo(WebhookEvent.Status.DELIVERED);
        assertThat(e.getDeliveredAt()).isNotNull();
        assertThat(received).singleElement().satisfies(r -> {
            long t = Long.parseLong(r[0].split(",")[0].substring(2));
            assertThat(r[0]).isEqualTo("t=" + t + ",v1=" + WebhookOutbox.sign("whsec_test", t, e.getPayload()));
            assertThat(r[1]).isEqualTo(e.getPayload());
        });
    }

    @Test
    void a_failed_attempt_is_retried_later_with_backoff() {
        failuresLeft.set(1);
        WebhookEvent e = due();

        delivery.deliverDue();

        assertThat(e.getStatus()).isEqualTo(WebhookEvent.Status.PENDING);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getLastError()).contains("500");
        assertThat(e.getNextAttemptAt()).isAfter(Instant.now().plus(Duration.ofMillis(500)));
    }

    @Test
    void gives_up_after_the_attempt_budget() {
        props.setWebhookMaxAttempts(2);
        failuresLeft.set(Integer.MAX_VALUE);
        WebhookEvent e = due();

        delivery.deliverDue();
        delivery.deliverDue();

        assertThat(e.getStatus()).isEqualTo(WebhookEvent.Status.FAILED);
        assertThat(e.getAttempts()).isEqualTo(2);
    }

    @Test
    void an_unreachable_partner_is_a_failed_attempt_not_a_crash() {
        partner.stop(0);
        WebhookEvent e = due();

        delivery.deliverDue();

        assertThat(e.getStatus()).isEqualTo(WebhookEvent.Status.PENDING);
        assertThat(e.getAttempts()).isEqualTo(1);
    }
}
