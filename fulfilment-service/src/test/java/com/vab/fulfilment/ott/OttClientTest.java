package com.vab.fulfilment.ott;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.NO_CONTENT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OttClient against a mock OTT partner API (Design/06 §8): the API key on every call, one attempt per call,
 * outcome + "uncertain" flag, and an error body (even a stack trace) is never copied into the failure detail.
 */
class OttClientTest {

    private static final String URL = "http://ott/ott/v1/entitlements";

    private static final String TRACE_BODY =
            "java.lang.RuntimeException: boom\n" + "\tat com.vab.ott.X.y(X.java:1)\n".repeat(50);

    private OttClient client;
    private MockRestServiceServer server;
    private CircuitBreakerRegistry breakers;

    @BeforeEach
    void wire() {
        RestClient.Builder builder = RestClient.builder();
        server   = MockRestServiceServer.bindTo(builder).build();
        breakers = CircuitBreakerRegistry.ofDefaults();
        client   = new OttClient(builder, breakers, "http://ott", "key-123");
    }

    @Test
    void accepted_202_is_pending_activation_and_sends_the_api_key() {
        server.expect(requestTo(URL))
              .andExpect(header(OttClient.API_KEY_HEADER, "key-123"))
              .andExpect(headerDoesNotExist("Authorization"))
              .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                      .body("{\"externalRef\":\"OTT-1\",\"status\":\"PENDING\"}"));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-d", null, null);

        assertThat(r.outcome()).isEqualTo(OttClient.Outcome.ACCEPTED);
        assertThat(r.externalRef()).isEqualTo("OTT-1");
        assertThat(r.uncertain()).isTrue();   // OTT now holds an entitlement for the order
        server.verify();
    }

    @Test
    void a_repeat_that_finds_it_active_is_provisioned_with_otts_validity() {
        server.expect(requestTo(URL))
              .andRespond(withSuccess("{\"externalRef\":\"OTT-1\",\"status\":\"ACTIVE\",\"validFrom\":\"2026-11-20T10:00:00Z\","
                      + "\"validUntil\":\"2027-05-20T10:00:00Z\"}", MediaType.APPLICATION_JSON));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-d", null, null);

        assertThat(r.outcome()).isEqualTo(OttClient.Outcome.PROVISIONED);
        assertThat(r.validUntil()).isEqualTo(Instant.parse("2027-05-20T10:00:00Z"));
        server.verify();
    }

    @Test
    void a_repeat_that_finds_it_rejected_is_rejected_with_the_reason() {
        server.expect(requestTo(URL))
              .andRespond(withSuccess("{\"externalRef\":\"OTT-1\",\"status\":\"REJECTED\",\"reason\":\"Plan not available\"}",
                      MediaType.APPLICATION_JSON));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-d", null, null);

        assertThat(r.outcome()).isEqualTo(OttClient.Outcome.REJECTED);
        assertThat(r.detail()).contains("Plan not available");
        server.verify();
    }

    @Test
    void rejection_422_keeps_provider_problem_detail_and_is_certain() {
        server.expect(ExpectedCount.once(), requestTo(URL))
              .andRespond(withStatus(UNPROCESSABLE_ENTITY)
                      .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                      .body("{\"detail\":\"Offer not provisionable: OFF-OTTBAD\"}"));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-OTTBAD", null, null);

        assertThat(r.outcome()).isEqualTo(OttClient.Outcome.REJECTED);
        assertThat(r.reason()).isEqualTo("PROVISIONING_REJECTED");
        assertThat(r.detail()).startsWith("HTTP 422").contains("Offer not provisionable: OFF-OTTBAD");
        assertThat(r.uncertain()).isFalse();
        server.verify();
    }

    @Test
    void non_problemjson_error_body_falls_back_to_status_without_throwing() {
        server.expect(ExpectedCount.once(), requestTo(URL))
              .andRespond(withStatus(UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON).body(TRACE_BODY));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-OTTBAD", null, null);

        assertThat(r.detail()).isEqualTo("HTTP 422 UNPROCESSABLE_ENTITY");
        server.verify();
    }

    @Test
    void unavailable_5xx_is_one_attempt_uncertain_with_short_detail() {
        server.expect(ExpectedCount.once(), requestTo(URL))
              .andRespond(withStatus(SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(TRACE_BODY));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-OTTDOWN", null, null);

        assertThat(r.outcome()).isEqualTo(OttClient.Outcome.UNAVAILABLE);
        assertThat(r.uncertain()).isTrue();   // a 5xx may still have committed at OTT
        assertThat(r.detail()).startsWith("HTTP 503").doesNotContain("RuntimeException", "at com.vab");
        assertThat(r.detail().length()).isLessThanOrEqualTo(183);
        server.verify();
    }

    @Test
    void wrong_api_key_401_is_unavailable_not_rejected() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-d", null, null);

        // A credentials problem is fixable; never refund a customer because our key was rotated.
        assertThat(r.outcome()).isEqualTo(OttClient.Outcome.UNAVAILABLE);
        server.verify();
    }

    @Test
    void open_breaker_fails_fast_without_calling_ott() {
        breakers.circuitBreaker("ott").transitionToOpenState();

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-d", null, null);

        assertThat(r.outcome()).isEqualTo(OttClient.Outcome.UNAVAILABLE);
        assertThat(r.uncertain()).isFalse();
        assertThat(client.isCallPermitted()).isFalse();
        server.verify();   // no request expected
    }

    @Test
    void revoke_by_order_deletes_with_the_api_key_and_reports_failures() {
        server.expect(requestTo(URL + "/by-order/ord-1")).andExpect(method(HttpMethod.DELETE))
              .andExpect(header(OttClient.API_KEY_HEADER, "key-123"))
              .andRespond(withStatus(NO_CONTENT));
        server.expect(requestTo(URL + "/by-order/ord-2")).andExpect(method(HttpMethod.DELETE))
              .andRespond(withStatus(SERVICE_UNAVAILABLE));

        assertThat(client.revokeByOrder("ord-1")).isTrue();
        assertThat(client.revokeByOrder("ord-2")).isFalse();
        server.verify();
    }
}
