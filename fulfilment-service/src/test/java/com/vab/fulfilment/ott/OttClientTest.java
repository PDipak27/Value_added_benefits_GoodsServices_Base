package com.vab.fulfilment.ott;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OttClient against a mock provider. The key guarantee (regression fix): an error
 * response body — even one carrying a stack trace — is NEVER copied into the failure
 * detail; only the status line is kept, so it stays safe for logs and saga_data_json.
 * Also pins the retry policy: 422 is not retried, 5xx is retried up to maxAttempts.
 */
class OttClientTest {

    private static final String URL = "http://ott/ott/v1/entitlements";

    /** Simulates a provider that leaked a stack trace in the body. */
    private static final String TRACE_BODY =
            "java.lang.RuntimeException: boom\n" + "\tat com.vab.ott.X.y(X.java:1)\n".repeat(50);

    private OttClient client;
    private MockRestServiceServer server;

    private void wire(int maxAttempts) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OttClient(builder, "http://ott", maxAttempts, 0L);
    }

    @Test
    void success_returns_external_ref() {
        wire(3);
        server.expect(requestTo(URL))
              .andRespond(withSuccess("{\"externalRef\":\"OTT-1\",\"status\":\"ACTIVE\"}",
                      MediaType.APPLICATION_JSON));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-d");

        assertThat(r.provisioned()).isTrue();
        assertThat(r.externalRef()).isEqualTo("OTT-1");
        server.verify();
    }

    @Test
    void rejection_422_is_not_retried_and_keeps_provider_problem_detail() {
        wire(3);
        server.expect(ExpectedCount.once(), requestTo(URL))
              .andRespond(withStatus(UNPROCESSABLE_ENTITY)
                      .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                      .body("{\"detail\":\"Offer not provisionable: OFF-OTTBAD\"}"));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-OTTBAD");

        assertThat(r.provisioned()).isFalse();
        assertThat(r.reason()).isEqualTo("PROVISIONING_REJECTED");
        assertThat(r.detail()).startsWith("HTTP 422").contains("Offer not provisionable: OFF-OTTBAD");
        server.verify(); // exactly one call — 422 is never retried
    }

    @Test
    void non_problemjson_error_body_falls_back_to_status_without_throwing() {
        // A trace / HTML body (e.g. from a proxy) must not break shortDetail nor leak.
        wire(3);
        server.expect(ExpectedCount.once(), requestTo(URL))
              .andRespond(withStatus(UNPROCESSABLE_ENTITY)
                      .contentType(MediaType.APPLICATION_JSON).body(TRACE_BODY));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-OTTBAD");

        assertThat(r.provisioned()).isFalse();
        assertThat(r.reason()).isEqualTo("PROVISIONING_REJECTED");
        assertThat(r.detail()).isEqualTo("HTTP 422 UNPROCESSABLE_ENTITY");
        assertThat(r.detail()).doesNotContain("RuntimeException", "at com.vab");
        server.verify();
    }

    @Test
    void malformed_problemjson_body_is_guarded_and_falls_back_to_status() {
        // Correct content-type but a broken body — the last-resort catch must hold.
        wire(3);
        server.expect(ExpectedCount.once(), requestTo(URL))
              .andRespond(withStatus(UNPROCESSABLE_ENTITY)
                      .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{not valid json"));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-OTTBAD");

        assertThat(r.detail()).isEqualTo("HTTP 422 UNPROCESSABLE_ENTITY");
        server.verify();
    }

    @Test
    void unavailable_5xx_retries_then_fails_with_short_detail() {
        wire(3);
        server.expect(ExpectedCount.times(3), requestTo(URL))
              .andRespond(withStatus(SERVICE_UNAVAILABLE)
                      .contentType(MediaType.APPLICATION_JSON).body(TRACE_BODY));

        OttClient.Result r = client.provision("ord-1", "sub-1", "OFF-OTTDOWN");

        assertThat(r.provisioned()).isFalse();
        assertThat(r.reason()).isEqualTo("PROVISIONING_UNAVAILABLE");
        assertThat(r.detail()).startsWith("HTTP 503").doesNotContain("RuntimeException", "at com.vab");
        assertThat(r.detail().length()).isLessThanOrEqualTo(183);
        server.verify(); // three attempts, then give up
    }
}
