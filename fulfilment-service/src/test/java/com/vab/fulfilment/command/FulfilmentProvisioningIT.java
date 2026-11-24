package com.vab.fulfilment.command;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.vab.events.fulfilment.CancelProvisioningCommand;
import com.vab.events.fulfilment.EntitlementRevokeFailed;
import com.vab.events.fulfilment.EntitlementRevoked;
import com.vab.events.fulfilment.FulfilOrderCommand;
import com.vab.events.fulfilment.OrderFulfilled;
import com.vab.events.fulfilment.OrderFulfilmentFailed;
import com.vab.events.fulfilment.OrderProvisioningCancelled;
import com.vab.events.fulfilment.RevokeEntitlementCommand;
import com.vab.fulfilment.it.AbstractIntegrationTest;
import com.vab.fulfilment.ott.OttWebhookController;
import com.vab.fulfilment.provisioning.ProvisioningPoller;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.commands.consumer.CommandReplyProducer;
import io.eventuate.tram.commands.consumer.CommandReplyToken;
import io.eventuate.tram.messaging.common.Message;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DIGITAL_SUBSCRIPTION provisioning with the real Spring wiring and Postgres (Design/06 §8): handler →
 * OttClient (API key, circuit breaker) → HTTP, the provisioning task table, the poller, and OTT's signed
 * webhooks (sent straight to {@link OttWebhookController}). WireMock plays OTT; the reply producer is mocked
 * so the replies can be inspected.
 */
@SpringBootTest
class FulfilmentProvisioningIT extends AbstractIntegrationTest {

    private static final String OTT_PATH = "/ott/v1/entitlements";
    private static final String WEBHOOK_SECRET = "whsec_it";

    static final WireMockServer wireMock = new WireMockServer(options().dynamicPort());

    static {
        wireMock.start();
    }

    @DynamicPropertySource
    static void externalEndpoints(DynamicPropertyRegistry r) {
        r.add("ott.base-url", wireMock::baseUrl);
        r.add("ott.api-key", () -> "ott_it_key");
        r.add("ott.webhook-secret", () -> WEBHOOK_SECRET);
        r.add("vab.fulfilment.provisioning.poll-interval", () -> "1h");   // the tests drive the poller
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Autowired FulfilmentCommandHandlers handlers;
    @Autowired ProvisioningPoller poller;
    @Autowired OttWebhookController webhooks;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CommandReplyProducer replies;

    private final CommandReplyToken token = new CommandReplyToken(Map.of("reply_saga_id", "saga-1"), "saga-reply");
    private String orderId;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
        breakers.getAllCircuitBreakers().forEach(cb -> cb.reset());
        orderId = "ord_it_" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <C> CommandMessage<C> cmd(C command) {
        CommandMessage<C> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(command);
        return cm;
    }

    private void fulfilDigital(String offerCode) {
        handlers.fulfil(cmd(new FulfilOrderCommand(orderId, "sub-premium", offerCode, "DIGITAL_SUBSCRIPTION", null, 6)), token);
    }

    private Message lastReply() {
        ArgumentCaptor<Message> reply = ArgumentCaptor.forClass(Message.class);
        // any(): a deferred reply uses the token deserialized from the task row, not this instance
        verify(replies).sendReplies(any(CommandReplyToken.class), reply.capture());
        return reply.getValue();
    }

    private void stubOttAccepted(String externalRef) {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"externalRef\":\"" + externalRef + "\",\"status\":\"PENDING\"}")));
    }

    /** OTT's webhook, signed the way ott-service signs it. */
    private int webhook(String eventId, String type, String externalRef, Instant validFrom, Instant validUntil) throws Exception {
        String body = """
                {"id":"%s","type":"%s","created":1,"data":{"externalRef":"%s","orderId":"%s","status":"X",\
                "validFrom":%s,"validUntil":%s,"reason":"Plan not available"}}""".formatted(eventId, type, externalRef, orderId,
                validFrom == null ? "null" : "\"" + validFrom + "\"", validUntil == null ? "null" : "\"" + validUntil + "\"");
        long t = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
        return webhooks.receive("t=" + t + ",v1=" + sig, body.getBytes(StandardCharsets.UTF_8)).getStatusCode().value();
    }

    private int activated(String externalRef) throws Exception {
        Instant from = Instant.parse("2026-11-20T10:00:00Z");
        return webhook("evt_" + UUID.randomUUID(), "entitlement.activated", externalRef, from, from.plus(Duration.ofDays(182)));
    }

    private int ottCalls() {
        return wireMock.findAll(postRequestedFor(urlEqualTo(OTT_PATH))).size();
    }

    private int recordsForOrder() {
        return jdbc.queryForObject("SELECT count(*) FROM fulfilment.fulfilments WHERE order_id = ?", Integer.class, orderId);
    }

    private List<Map<String, Object>> task() {
        return jdbc.queryForList("SELECT * FROM fulfilment.provisioning_task WHERE order_id = ?", orderId);
    }

    private void makeTaskDue() {
        jdbc.update("UPDATE fulfilment.provisioning_task SET next_attempt_at = now() - interval '1 second' WHERE order_id = ?", orderId);
    }

    // ── first attempt ───────────────────────────────────────────────────────

    @Test
    void accepted_waits_for_the_activation_webhook_then_records_the_entitlement() throws Exception {
        stubOttAccepted("OTT-IT1");

        fulfilDigital("OTT_NETFLIX_6M");

        verify(replies, never()).sendReplies(any(), any(Message.class));
        assertThat(task()).singleElement().satisfies(t -> {
            assertThat(t.get("status")).isEqualTo("AWAITING_ACTIVATION");
            assertThat(t.get("external_ref")).isEqualTo("OTT-IT1");
        });

        assertThat(activated("OTT-IT1")).isEqualTo(200);

        OrderFulfilled reply = Replies.assertSuccess(lastReply(), OrderFulfilled.class);
        assertThat(reply.getExternalRef()).isEqualTo("OTT-IT1");
        assertThat(Duration.between(reply.getValidFrom(), reply.getValidUntil()).toDays()).isEqualTo(182);
        assertThat(jdbc.queryForMap("SELECT * FROM fulfilment.fulfilments WHERE order_id = ?", orderId).get("external_ref"))
                .isEqualTo("OTT-IT1");
        assertThat(task()).isEmpty();
    }

    @Test
    void ott_request_carries_the_api_key_and_order_details() {
        stubOttAccepted("OTT-IT2");

        fulfilDigital("OTT_NETFLIX_6M");

        wireMock.verify(postRequestedFor(urlEqualTo(OTT_PATH))
                .withHeader("X-Api-Key", equalTo("ott_it_key"))
                .withoutHeader("Authorization")
                .withRequestBody(matchingJsonPath("$.orderId", equalTo(orderId)))
                .withRequestBody(matchingJsonPath("$.subscriberId", equalTo("sub-premium")))
                .withRequestBody(matchingJsonPath("$.offerCode", equalTo("OTT_NETFLIX_6M"))));
    }

    @Test
    void a_duplicate_activation_webhook_is_applied_once() throws Exception {
        stubOttAccepted("OTT-IT3");
        fulfilDigital("OTT_NETFLIX_6M");
        Instant from = Instant.now();

        assertThat(webhook("evt_dup_" + orderId, "entitlement.activated", "OTT-IT3", from, null)).isEqualTo(200);
        assertThat(webhook("evt_dup_" + orderId, "entitlement.activated", "OTT-IT3", from, null)).isEqualTo(200);

        Replies.assertSuccess(lastReply(), OrderFulfilled.class);   // verify(...) = exactly one reply
        assertThat(recordsForOrder()).isEqualTo(1);
    }

    @Test
    void an_unsigned_webhook_is_rejected() {
        stubOttAccepted("OTT-IT4");
        fulfilDigital("OTT_NETFLIX_6M");

        String body = "{\"id\":\"evt_x\",\"type\":\"entitlement.activated\",\"data\":{\"orderId\":\"" + orderId + "\"}}";
        assertThat(webhooks.receive(null, body.getBytes(StandardCharsets.UTF_8)).getStatusCode().value()).isEqualTo(401);
        assertThat(task()).singleElement().satisfies(t -> assertThat(t.get("status")).isEqualTo("AWAITING_ACTIVATION"));
    }

    @Test
    void rejection_by_webhook_replies_failed() throws Exception {
        stubOttAccepted("OTT-IT5");
        fulfilDigital("OTTREJECT_1M");

        webhook("evt_rej_" + orderId, "entitlement.rejected", "OTT-IT5", null, null);

        OrderFulfilmentFailed reply = Replies.assertSuccess(lastReply(), OrderFulfilmentFailed.class);
        assertThat(reply.getReason()).isEqualTo("PROVISIONING_REJECTED");
        assertThat(reply.getDetail()).contains("Plan not available");
        assertThat(task()).isEmpty();
        assertThat(recordsForOrder()).isZero();
    }

    @Test
    void a_repeat_that_finds_the_entitlement_active_completes_at_once() {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"externalRef\":\"OTT-IT6\",\"status\":\"ACTIVE\",\"validFrom\":\"2026-11-20T10:00:00Z\","
                        + "\"validUntil\":\"2027-05-20T10:00:00Z\"}")));

        fulfilDigital("OTT_NETFLIX_6M");

        OrderFulfilled reply = Replies.assertSuccess(lastReply(), OrderFulfilled.class);
        assertThat(reply.getExternalRef()).isEqualTo("OTT-IT6");
        assertThat(reply.getValidUntil()).isEqualTo(Instant.parse("2027-05-20T10:00:00Z"));
        assertThat(task()).isEmpty();
    }

    @Test
    void rejection_422_replies_failed_at_once() {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":422,\"detail\":\"Offer not provisionable: OTTBAD_1M\"}")));

        fulfilDigital("OTTBAD_1M");

        OrderFulfilmentFailed reply = Replies.assertSuccess(lastReply(), OrderFulfilmentFailed.class);
        assertThat(reply.getReason()).isEqualTo("PROVISIONING_REJECTED");
        assertThat(reply.getDetail()).contains("Offer not provisionable");
        assertThat(ottCalls()).isEqualTo(1);
        assertThat(task()).isEmpty();
        assertThat(recordsForOrder()).isZero();
    }

    @Test
    void ott_down_defers_the_reply_to_a_task() {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(503)));

        fulfilDigital("OTT_NETFLIX_6M");

        verify(replies, never()).sendReplies(any(), any(Message.class));
        assertThat(ottCalls()).isEqualTo(1);
        assertThat(task()).singleElement().satisfies(t -> {
            assertThat(t.get("status")).isEqualTo("RETRYING");
            assertThat(t.get("ott_uncertain")).isEqualTo(true);
        });
    }

    @Test
    void provider_stack_trace_does_not_leak_into_the_task() {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "text/plain")
                .withBody("java.lang.NullPointerException\n\tat com.ott.Internal.secret(Internal.java:42)")));

        fulfilDigital("OTT_NETFLIX_6M");

        assertThat((String) task().get(0).get("last_detail")).contains("500").doesNotContain("NullPointerException", "Internal.java");
    }

    // ── poller ──────────────────────────────────────────────────────────────

    @Test
    void poller_retry_that_is_accepted_waits_for_activation() throws Exception {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(503)));
        fulfilDigital("OTT_NETFLIX_6M");
        stubOttAccepted("OTT-LATE");
        makeTaskDue();

        poller.tick();

        verify(replies, never()).sendReplies(any(), any(Message.class));
        assertThat(task()).singleElement().satisfies(t -> assertThat(t.get("status")).isEqualTo("AWAITING_ACTIVATION"));

        activated("OTT-LATE");

        assertThat(Replies.assertSuccess(lastReply(), OrderFulfilled.class).getExternalRef()).isEqualTo("OTT-LATE");
        assertThat(task()).isEmpty();
        assertThat(recordsForOrder()).isEqualTo(1);
    }

    @Test
    void poller_replies_timeout_once_the_deadline_passed() {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(503)));
        fulfilDigital("OTT_NETFLIX_6M");
        jdbc.update("UPDATE fulfilment.provisioning_task SET deadline_at = now() - interval '1 second' WHERE order_id = ?", orderId);

        poller.tick();

        assertThat(Replies.assertSuccess(lastReply(), OrderFulfilmentFailed.class).getReason()).isEqualTo("PROVISIONING_TIMEOUT");
        assertThat(task()).singleElement().satisfies(t -> assertThat(t.get("status")).isEqualTo("REVOKE_OWED"));
    }

    @Test
    void activation_never_arriving_times_out_and_a_late_one_is_revoked() throws Exception {
        stubOttAccepted("OTT-SLOW");
        fulfilDigital("OTT_NETFLIX_6M");
        jdbc.update("UPDATE fulfilment.provisioning_task SET deadline_at = now() - interval '1 second' WHERE order_id = ?", orderId);

        poller.tick();

        assertThat(Replies.assertSuccess(lastReply(), OrderFulfilmentFailed.class).getReason()).isEqualTo("PROVISIONING_TIMEOUT");
        assertThat(task()).singleElement().satisfies(t -> assertThat(t.get("status")).isEqualTo("REVOKE_OWED"));

        clearInvocations(replies);
        activated("OTT-SLOW");

        verify(replies, never()).sendReplies(any(), any(Message.class));   // the saga already has its answer
        assertThat(task()).singleElement().satisfies(t -> assertThat(t.get("status")).isEqualTo("REVOKE_OWED"));
    }

    @Test
    void user_cancel_while_waiting_replies_cancelled_and_revokes_later() {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(503)));
        fulfilDigital("OTT_NETFLIX_6M");

        handlers.cancelProvisioning(cmd(new CancelProvisioningCommand(orderId, "USER_CANCEL")));
        poller.tick();

        assertThat(Replies.assertSuccess(lastReply(), OrderProvisioningCancelled.class).getReason()).isEqualTo("USER_CANCEL");
        assertThat(task()).singleElement().satisfies(t -> assertThat(t.get("status")).isEqualTo("REVOKE_OWED"));

        wireMock.stubFor(delete(urlEqualTo(OTT_PATH + "/by-order/" + orderId)).willReturn(aResponse().withStatus(204)));
        makeTaskDue();
        clearInvocations(replies);
        poller.tick();

        assertThat(task()).isEmpty();
        verify(replies, never()).sendReplies(any(), any(Message.class));
    }

    @Test
    void user_cancel_while_awaiting_activation_refunds_and_revokes() {
        stubOttAccepted("OTT-CXL");
        fulfilDigital("OTT_NETFLIX_6M");

        handlers.cancelProvisioning(cmd(new CancelProvisioningCommand(orderId, "USER_CANCEL")));
        poller.tick();

        assertThat(Replies.assertSuccess(lastReply(), OrderProvisioningCancelled.class).getReason()).isEqualTo("USER_CANCEL");
        assertThat(task()).singleElement().satisfies(t -> assertThat(t.get("status")).isEqualTo("REVOKE_OWED"));
    }

    @Test
    void cancel_before_the_fulfil_command_answers_cancelled_without_calling_ott() {
        handlers.cancelProvisioning(cmd(new CancelProvisioningCommand(orderId, "USER_CANCEL")));

        fulfilDigital("OTT_NETFLIX_6M");

        Replies.assertSuccess(lastReply(), OrderProvisioningCancelled.class);
        assertThat(ottCalls()).isZero();
        assertThat(task()).isEmpty();
    }

    // ── revoke (Phase 3) ────────────────────────────────────────────────────

    @Test
    void revoke_calls_ott_delete() {
        wireMock.stubFor(delete(urlEqualTo(OTT_PATH + "/OTT-REV1")).willReturn(aResponse().withStatus(204)));

        Message reply = handlers.revokeEntitlement(cmd(new RevokeEntitlementCommand(orderId, "DIGITAL_SUBSCRIPTION", "OTT-REV1")));

        assertThat(Replies.assertSuccess(reply, EntitlementRevoked.class).getOrderId()).isEqualTo(orderId);
        wireMock.verify(deleteRequestedFor(urlEqualTo(OTT_PATH + "/OTT-REV1")).withHeader("X-Api-Key", equalTo("ott_it_key")));
    }

    @Test
    void revoke_failure_at_ott_is_reported() {
        wireMock.stubFor(delete(urlEqualTo(OTT_PATH + "/OTT-REV2")).willReturn(aResponse().withStatus(500)));

        Message reply = handlers.revokeEntitlement(cmd(new RevokeEntitlementCommand(orderId, "DIGITAL_SUBSCRIPTION", "OTT-REV2")));

        assertThat(Replies.assertSuccess(reply, EntitlementRevokeFailed.class).getReason()).isEqualTo("OTT_REVOKE_FAILED");
    }

    @Test
    void license_revoke_does_not_call_ott() {
        Message reply = handlers.revokeEntitlement(cmd(new RevokeEntitlementCommand(orderId, "SOFTWARE_LICENSE", null)));

        Replies.assertSuccess(reply, EntitlementRevoked.class);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }
}
