package com.vab.fulfilment.command;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.vab.events.fulfilment.EntitlementRevokeFailed;
import com.vab.events.fulfilment.EntitlementRevoked;
import com.vab.events.fulfilment.FulfilOrderCommand;
import com.vab.events.fulfilment.OrderFulfilled;
import com.vab.events.fulfilment.OrderProvisioningFailed;
import com.vab.events.fulfilment.RevokeEntitlementCommand;
import com.vab.fulfilment.it.AbstractIntegrationTest;
import com.vab.fulfilment.ott.KeycloakTokenProvider;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DIGITAL_SUBSCRIPTION fulfilment (DD-27, §A-1) with the real Spring wiring: FulfilmentCommandHandlers →
 * OttProvisioningService → OttClient (+ KeycloakTokenProvider) → HTTP, and the audit row in Postgres.
 * WireMock plays the external OTT provider and Keycloak's token endpoint, so retries, the 422 no-retry
 * rule, the client-credentials token and the parked {@code OrderProvisioningFailed} reply are all real.
 */
@SpringBootTest
class FulfilmentProvisioningIT extends AbstractIntegrationTest {

    private static final String TOKEN_PATH = "/realms/vab/protocol/openid-connect/token";
    private static final String OTT_PATH = "/ott/v1/entitlements";

    static final WireMockServer wireMock = new WireMockServer(options().dynamicPort());

    static {
        wireMock.start();
    }

    @DynamicPropertySource
    static void externalEndpoints(DynamicPropertyRegistry r) {
        r.add("ott.base-url", wireMock::baseUrl);
        r.add("keycloak.token-uri", () -> wireMock.baseUrl() + TOKEN_PATH);
        r.add("ott.max-attempts", () -> 3);
        r.add("ott.retry-delay-ms", () -> 10);
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Autowired FulfilmentCommandHandlers handlers;
    @Autowired KeycloakTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;

    private String orderId;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
        ReflectionTestUtils.setField(tokenProvider, "cachedToken", null);   // each test starts with no cached token
        wireMock.stubFor(post(urlEqualTo(TOKEN_PATH))
                .willReturn(okJson("{\"access_token\":\"tok-1\",\"expires_in\":300,\"token_type\":\"Bearer\"}")));
        orderId = "ord_it_" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <C> CommandMessage<C> cmd(C command) {
        CommandMessage<C> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(command);
        return cm;
    }

    private Message fulfilDigital(String offerCode) {
        return handlers.fulfil(cmd(new FulfilOrderCommand(orderId, "sub-premium", offerCode, "DIGITAL_SUBSCRIPTION", null, 6)));
    }

    private void stubOttCreated(String externalRef) {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"externalRef\":\"" + externalRef + "\",\"status\":\"ACTIVE\"}")));
    }

    private int ottCalls() {
        return wireMock.findAll(postRequestedFor(urlEqualTo(OTT_PATH))).size();
    }

    private int recordsForOrder() {
        return jdbc.queryForObject("SELECT count(*) FROM fulfilment.fulfilments WHERE order_id = ?", Integer.class, orderId);
    }

    // ── provisioning ────────────────────────────────────────────────────────

    /** Happy path: OTT 201 → OrderFulfilled with the OTT externalRef and a 6-month validity window, plus a DIGITAL_SUBSCRIPTION audit row. */
    @Test
    void provisions_and_records_entitlement() {
        stubOttCreated("OTT-IT1");

        OrderFulfilled reply = Replies.assertSuccess(fulfilDigital("OTT_NETFLIX_6M"), OrderFulfilled.class);

        assertThat(reply.getExternalRef()).isEqualTo("OTT-IT1");
        assertThat(Duration.between(reply.getValidFrom(), reply.getValidUntil()).toDays()).isBetween(180L, 185L);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM fulfilment.fulfilments WHERE order_id = ?", orderId);
        assertThat(row.get("product_type")).isEqualTo("DIGITAL_SUBSCRIPTION");
        assertThat(row.get("external_ref")).isEqualTo("OTT-IT1");
        assertThat(row.get("status")).isEqualTo("FULFILLED");
    }

    /** The OTT call carries the Keycloak Bearer and the order/subscriber/offer in its JSON body. */
    @Test
    void ott_request_carries_bearer_and_order_details() {
        stubOttCreated("OTT-IT2");

        fulfilDigital("OTT_NETFLIX_6M");

        wireMock.verify(postRequestedFor(urlEqualTo(OTT_PATH))
                .withHeader("Authorization", equalTo("Bearer tok-1"))
                .withRequestBody(matchingJsonPath("$.orderId", equalTo(orderId)))
                .withRequestBody(matchingJsonPath("$.subscriberId", equalTo("sub-premium")))
                .withRequestBody(matchingJsonPath("$.offerCode", equalTo("OTT_NETFLIX_6M"))));
    }

    /** The token is a client_credentials grant for vab-provisioning with scope ott:provision, fetched once and cached across calls. */
    @Test
    void client_credentials_token_is_requested_once_and_cached() {
        stubOttCreated("OTT-IT3");

        fulfilDigital("OTT_NETFLIX_6M");
        orderId = orderId + "b";
        fulfilDigital("OTT_NETFLIX_6M");

        wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=vab-provisioning"))
                .withRequestBody(matching(".*scope=ott(%3A|:)provision.*")));
        assertThat(ottCalls()).isEqualTo(2);
    }

    /** Transient 5xx is retried in-call: 503, 503, then 201 → provisioned on the third attempt. */
    @Test
    void transient_5xx_is_retried_until_success() {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("one-failed"));
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).inScenario("flaky").whenScenarioStateIs("one-failed")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("two-failed"));
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).inScenario("flaky").whenScenarioStateIs("two-failed")
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"externalRef\":\"OTT-RETRY\",\"status\":\"ACTIVE\"}")));

        OrderFulfilled reply = Replies.assertSuccess(fulfilDigital("OTT_NETFLIX_6M"), OrderFulfilled.class);

        assertThat(reply.getExternalRef()).isEqualTo("OTT-RETRY");
        assertThat(ottCalls()).isEqualTo(3);
    }

    /** OTT down for all attempts → parked: OrderProvisioningFailed(PROVISIONING_UNAVAILABLE) after exactly max-attempts calls, no audit row. */
    @Test
    void persistent_5xx_parks_after_max_attempts() {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(503)));

        OrderProvisioningFailed reply = Replies.assertSuccess(fulfilDigital("OTT_NETFLIX_6M"), OrderProvisioningFailed.class);

        assertThat(reply.getReason()).isEqualTo("PROVISIONING_UNAVAILABLE");
        assertThat(reply.getDetail()).contains("503");
        assertThat(ottCalls()).isEqualTo(3);
        assertThat(recordsForOrder()).isZero();
    }

    /** A 422 is a hard rejection: one call only (no retry), PROVISIONING_REJECTED with the provider's ProblemDetail message. */
    @Test
    void rejection_422_is_not_retried() {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":422,\"detail\":\"Offer not provisionable: OTTBAD_1M\"}")));

        OrderProvisioningFailed reply = Replies.assertSuccess(fulfilDigital("OTTBAD_1M"), OrderProvisioningFailed.class);

        assertThat(reply.getReason()).isEqualTo("PROVISIONING_REJECTED");
        assertThat(reply.getDetail()).contains("Offer not provisionable");
        assertThat(ottCalls()).isEqualTo(1);
        assertThat(recordsForOrder()).isZero();
    }

    /** A provider 500 whose body is a stack trace never leaks into the reply detail (only the status line). */
    @Test
    void provider_stack_trace_does_not_leak_into_reply() {
        wireMock.stubFor(post(urlEqualTo(OTT_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "text/plain")
                .withBody("java.lang.NullPointerException\n\tat com.ott.Internal.secret(Internal.java:42)")));

        OrderProvisioningFailed reply = Replies.assertSuccess(fulfilDigital("OTT_NETFLIX_6M"), OrderProvisioningFailed.class);

        assertThat(reply.getDetail()).contains("500").doesNotContain("NullPointerException", "Internal.java");
    }

    /** Keycloak unavailable: no token → OTT is never called, and the order parks as PROVISIONING_UNAVAILABLE. */
    @Test
    void token_endpoint_down_parks_without_calling_ott() {
        wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(503)));
        stubOttCreated("OTT-NEVER");

        OrderProvisioningFailed reply = Replies.assertSuccess(fulfilDigital("OTT_NETFLIX_6M"), OrderProvisioningFailed.class);

        assertThat(reply.getReason()).isEqualTo("PROVISIONING_UNAVAILABLE");
        assertThat(ottCalls()).isZero();
    }

    // ── revoke (Phase 3) ────────────────────────────────────────────────────

    /** Revoke of a DIGITAL_SUBSCRIPTION issues an authenticated OTT DELETE for the externalRef and replies EntitlementRevoked. */
    @Test
    void revoke_calls_ott_delete() {
        wireMock.stubFor(delete(urlEqualTo(OTT_PATH + "/OTT-REV1")).willReturn(aResponse().withStatus(204)));

        Message reply = handlers.revokeEntitlement(cmd(new RevokeEntitlementCommand(orderId, "DIGITAL_SUBSCRIPTION", "OTT-REV1")));

        assertThat(Replies.assertSuccess(reply, EntitlementRevoked.class).getOrderId()).isEqualTo(orderId);
        wireMock.verify(deleteRequestedFor(urlEqualTo(OTT_PATH + "/OTT-REV1")).withHeader("Authorization", equalTo("Bearer tok-1")));
    }

    /** An OTT error on revoke yields EntitlementRevokeFailed(OTT_REVOKE_FAILED) rather than an exception. */
    @Test
    void revoke_failure_at_ott_is_reported() {
        wireMock.stubFor(delete(urlEqualTo(OTT_PATH + "/OTT-REV2")).willReturn(aResponse().withStatus(500)));

        Message reply = handlers.revokeEntitlement(cmd(new RevokeEntitlementCommand(orderId, "DIGITAL_SUBSCRIPTION", "OTT-REV2")));

        assertThat(Replies.assertSuccess(reply, EntitlementRevokeFailed.class).getReason()).isEqualTo("OTT_REVOKE_FAILED");
    }

    /** SOFTWARE_LICENSE revoke is read-model-only: EntitlementRevoked without any call to OTT. */
    @Test
    void license_revoke_does_not_call_ott() {
        Message reply = handlers.revokeEntitlement(cmd(new RevokeEntitlementCommand(orderId, "SOFTWARE_LICENSE", null)));

        Replies.assertSuccess(reply, EntitlementRevoked.class);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }
}
