package com.vab.billing.it;

import com.vab.billing.payment.PaymentService;
import com.vab.billing.payment.PspClient;
import com.vab.billing.payment.PspClient.CallResult;
import com.vab.billing.payment.PspClient.IntentResult;
import com.vab.billing.payment.PspClient.Outcome;
import com.vab.events.billing.AwaitPaymentCommand;
import com.vab.events.billing.CreatePaymentIntentCommand;
import com.vab.events.billing.PaymentAuthorized;
import com.vab.events.billing.PaymentIntentCreated;
import com.vab.events.billing.PaymentIntentFailed;
import com.vab.events.billing.PaymentNotCompleted;
import io.eventuate.common.json.mapper.JSonMapper;
import io.eventuate.tram.commands.common.ReplyMessageHeaders;
import io.eventuate.tram.commands.consumer.CommandReplyProducer;
import io.eventuate.tram.commands.consumer.CommandReplyToken;
import io.eventuate.tram.messaging.common.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PAY_NOW in billing against real Postgres (Design/15): intent row, deferred reply token, a signed webhook
 * over real HTTP, dedupe, cancel tombstone and expiry. The provider client and the reply producer are mocked;
 * the sweeper is slowed down so only the test drives it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"vab.billing.payments.sweep-interval=1h", "vab.billing.payments.webhook-secret=whsec_it"})
class PaymentFlowIT extends AbstractIntegrationTest {

    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;

    @MockitoBean PspClient psp;
    @MockitoBean CommandReplyProducer replies;

    private final CommandReplyToken token = new CommandReplyToken(Map.of("command_saga_id", "saga-it"), "saga-reply");

    private String newOrder() {
        return "ord-it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String openIntent(String orderId) {
        String intentId = "pi_" + UUID.randomUUID().toString().substring(0, 8);
        when(psp.createIntent(eq(orderId), anyLong(), anyString()))
                .thenReturn(new IntentResult(Outcome.OK, intentId, "http://psp/checkout/" + intentId, Instant.now().plusSeconds(480), null));
        Message created = payments.createIntent(new CreatePaymentIntentCommand(orderId, "sub-it", 500, "INR"));
        assertThat(JSonMapper.fromJson(created.getPayload(), PaymentIntentCreated.class).getIntentId()).isEqualTo(intentId);
        return intentId;
    }

    private String status(String orderId) {
        return jdbc.queryForObject("SELECT status FROM billing.payment_intent WHERE order_id = ?", String.class, orderId);
    }

    private ResponseEntity<Void> webhook(String eventId, String type, String intentId) throws Exception {
        String body = """
                {"id":"%s","type":"%s","created":1,"data":{"intentId":"%s","failureReason":"customer_declined"}}"""
                .formatted(eventId, type, intentId);
        long t = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_it".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Psp-Signature", "t=" + t + ",v1=" + sig);
        return http.postForEntity("/webhooks/psp", new HttpEntity<>(body, headers), Void.class);
    }

    private Message onlyReply() {
        ArgumentCaptor<Message> m = ArgumentCaptor.forClass(Message.class);
        verify(replies).sendReplies(any(CommandReplyToken.class), m.capture());
        return m.getValue();
    }

    private static <T> T payload(Message m, String outcome, Class<T> type) {
        assertThat(m.getRequiredHeader(ReplyMessageHeaders.REPLY_OUTCOME)).isEqualTo(outcome);
        return JSonMapper.fromJson(m.getPayload(), type);
    }

    @Test
    void paid_webhook_answers_the_waiting_saga_once() throws Exception {
        String orderId = newOrder();
        String intentId = openIntent(orderId);
        payments.awaitPayment(new AwaitPaymentCommand(orderId, intentId), token);
        verifyNoInteractions(replies);

        assertThat(webhook("evt_" + intentId, "payment.authorized", intentId).getStatusCode().value()).isEqualTo(200);
        assertThat(webhook("evt_" + intentId, "payment.authorized", intentId).getStatusCode().value()).isEqualTo(200);

        assertThat(payload(onlyReply(), "SUCCESS", PaymentAuthorized.class).getAuthId()).isEqualTo(intentId);
        assertThat(status(orderId)).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("SELECT reply_token_json FROM billing.payment_intent WHERE order_id = ?",
                String.class, orderId)).isNull();
    }

    @Test
    void unsigned_webhook_is_rejected_and_changes_nothing() {
        String orderId = newOrder();
        String intentId = openIntent(orderId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Void> res = http.postForEntity("/webhooks/psp", new HttpEntity<>(
                "{\"id\":\"evt_x\",\"type\":\"payment.authorized\",\"data\":{\"intentId\":\"" + intentId + "\"}}", headers), Void.class);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(status(orderId)).isEqualTo("CREATED");
    }

    @Test
    void decline_tells_the_saga() throws Exception {
        String orderId = newOrder();
        String intentId = openIntent(orderId);
        payments.awaitPayment(new AwaitPaymentCommand(orderId, intentId), token);

        webhook("evt_d_" + intentId, "payment.failed", intentId);

        assertThat(payload(onlyReply(), "FAILURE", PaymentNotCompleted.class).getReason()).isEqualTo("DECLINED");
        assertThat(status(orderId)).isEqualTo("FAILED");
    }

    @Test
    void cancel_before_the_intent_exists_refuses_it_later() {
        String orderId = newOrder();
        payments.cancelPayment(orderId, "USER_CANCEL");

        Message reply = payments.createIntent(new CreatePaymentIntentCommand(orderId, "sub-it", 500, "INR"));

        assertThat(payload(reply, "FAILURE", PaymentIntentFailed.class).getReason()).isEqualTo("CANCELLED");
        verify(psp, never()).createIntent(anyString(), anyLong(), anyString());
    }

    @Test
    void cancel_while_paying_closes_the_intent_and_tells_the_saga() {
        String orderId = newOrder();
        String intentId = openIntent(orderId);
        when(psp.cancel(intentId)).thenReturn(new CallResult(Outcome.OK, null));
        payments.awaitPayment(new AwaitPaymentCommand(orderId, intentId), token);

        payments.cancelPayment(orderId, "USER_CANCEL");

        assertThat(payload(onlyReply(), "FAILURE", PaymentNotCompleted.class).getReason()).isEqualTo("CANCELLED");
        assertThat(status(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    void sweeper_expires_an_unpaid_intent_and_a_late_payment_is_voided() throws Exception {
        String orderId = newOrder();
        String intentId = openIntent(orderId);
        when(psp.cancel(intentId)).thenReturn(new CallResult(Outcome.OK, null));
        payments.awaitPayment(new AwaitPaymentCommand(orderId, intentId), token);
        jdbc.update("UPDATE billing.payment_intent SET expires_at = ? WHERE order_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), orderId);

        payments.expireDue();
        assertThat(payload(onlyReply(), "FAILURE", PaymentNotCompleted.class).getReason()).isEqualTo("EXPIRED");
        assertThat(status(orderId)).isEqualTo("EXPIRED");

        webhook("evt_late_" + intentId, "payment.authorized", intentId);
        assertThat(status(orderId)).isEqualTo("VOIDED");
    }

    /**
     * Two writers on one row: the sweeper holds the row lock (inside its slow provider call) when the
     * "authorized" webhook arrives. The webhook must wait for the lock, then see EXPIRED and void; the saga
     * gets exactly one reply.
     */
    @Test
    void webhook_racing_the_sweeper_waits_for_the_lock_and_the_saga_gets_one_reply() throws Exception {
        String orderId = newOrder();
        String intentId = openIntent(orderId);
        payments.awaitPayment(new AwaitPaymentCommand(orderId, intentId), token);
        jdbc.update("UPDATE billing.payment_intent SET expires_at = ? WHERE order_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), orderId);

        CountDownLatch sweeperHoldsLock = new CountDownLatch(1);
        AtomicInteger cancels = new AtomicInteger();
        when(psp.cancel(intentId)).thenAnswer(inv -> {
            if (cancels.incrementAndGet() == 1) {   // the sweeper's cancel, inside its row lock
                sweeperHoldsLock.countDown();
                Thread.sleep(1000);                 // well under lock_timeout (3 s)
            }
            return new CallResult(Outcome.OK, null);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> sweeper = pool.submit(() -> payments.expireDue());
            assertThat(sweeperHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            Future<ResponseEntity<Void>> hook = pool.submit(() -> webhook("evt_race_" + intentId, "payment.authorized", intentId));

            assertThat(sweeper.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(hook.get(20, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("the webhook waited for the row lock")
                    .isGreaterThan(Duration.ofMillis(500));
        } finally {
            pool.shutdownNow();
        }

        assertThat(payload(onlyReply(), "FAILURE", PaymentNotCompleted.class).getReason()).isEqualTo("EXPIRED");
        assertThat(status(orderId)).isEqualTo("VOIDED");
        assertThat(cancels).hasValue(2);   // expire, then void the late authorisation
    }
}
