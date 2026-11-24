package com.vab.paymentsim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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

/**
 * Webhook delivery against a real local HTTP endpoint: signed POST, retry with backoff until 2xx, and
 * give up after the attempt budget. Backoff is capped at 50 ms so the test runs fast.
 */
class WebhookDeliveryTest {

    private record Received(String body, String signature) {}

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final PspProperties props = new PspProperties();
    private WebhookDispatcher dispatcher;

    @BeforeEach
    void start() throws Exception {
        startServer();
        props.setWebhookSecret("whsec_test");
        props.setWebhookMaxBackoff(Duration.ofMillis(50));
        dispatcher = new WebhookDispatcher(props, new ObjectMapper(), RestClient.builder());
    }

    /** A merchant endpoint on a free port; the dispatcher reads the URL from props on every send. */
    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhooks/psp", ex -> {
            received.add(new Received(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    ex.getRequestHeaders().getFirst(WebhookDispatcher.SIGNATURE_HEADER)));
            int status = failuresLeft.getAndDecrement() > 0 ? 500 : 200;
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        props.setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks/psp");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static PaymentIntent authorizedIntent() {
        PaymentIntent i = new PaymentIntent("pi_1", "cs_1", "ord-1", 500, "INR", Instant.now(), Instant.now().plusSeconds(60));
        i.setStatus(PaymentIntent.Status.AUTHORIZED);
        return i;
    }

    /** Ticks until {@code deliveries} requests have arrived, as the @Scheduled loop would. */
    private void tickUntil(int deliveries) throws InterruptedException {
        for (int i = 0; i < 100 && received.size() < deliveries; i++) {
            dispatcher.deliverDue();
            Thread.sleep(20);
        }
    }

    @Test
    void delivers_a_signed_event_once() throws Exception {
        dispatcher.publish("payment.authorized", authorizedIntent());

        tickUntil(1);
        Thread.sleep(100);
        dispatcher.deliverDue();   // delivered: nothing left to send

        assertThat(received).hasSize(1);
        Received r = received.get(0);
        JsonNode event = new ObjectMapper().readTree(r.body());
        assertThat(event.path("id").asText()).startsWith("evt_");
        assertThat(event.path("type").asText()).isEqualTo("payment.authorized");
        assertThat(event.path("data").path("intentId").asText()).isEqualTo("pi_1");
        assertThat(event.path("data").path("status").asText()).isEqualTo("AUTHORIZED");

        String[] parts = r.signature().split(",");
        long t = Long.parseLong(parts[0].substring(2));
        assertThat(parts[1]).isEqualTo("v1=" + WebhookDispatcher.sign("whsec_test", t, r.body()));
        assertThat(Math.abs(Instant.now().getEpochSecond() - t)).isLessThan(10);
    }

    @Test
    void retries_until_the_merchant_answers_2xx_with_the_same_event() throws Exception {
        failuresLeft.set(2);
        dispatcher.publish("payment.authorized", authorizedIntent());

        tickUntil(3);
        Thread.sleep(100);
        dispatcher.deliverDue();

        assertThat(received).hasSize(3);
        String eventId = new ObjectMapper().readTree(received.get(0).body()).path("id").asText();
        assertThat(received).allSatisfy(r -> assertThat(r.body()).contains(eventId));   // same event id → billing dedupes
    }

    @Test
    void waits_for_the_backoff_before_retrying() {
        props.setWebhookMaxBackoff(Duration.ofMinutes(1));
        failuresLeft.set(1);
        dispatcher.publish("payment.failed", authorizedIntent());

        dispatcher.deliverDue();
        dispatcher.deliverDue();   // inside the backoff window: no second attempt yet

        assertThat(received).hasSize(1);
    }

    @Test
    void gives_up_after_the_attempt_budget() throws Exception {
        props.setWebhookMaxAttempts(3);
        failuresLeft.set(Integer.MAX_VALUE);
        dispatcher.publish("payment.authorized", authorizedIntent());

        tickUntil(3);
        for (int i = 0; i < 10; i++) {
            Thread.sleep(60);
            dispatcher.deliverDue();
        }

        assertThat(received).hasSize(3);
    }

    @Test
    void an_unreachable_merchant_is_retried_not_dropped() throws Exception {
        server.stop(0);
        dispatcher.publish("payment.authorized", authorizedIntent());
        dispatcher.deliverDue();   // connection refused → kept, retried after the backoff
        assertThat(received).isEmpty();

        startServer();
        tickUntil(1);

        assertThat(received).hasSize(1);
    }
}
