package com.vab.lite.e2e;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

/**
 * Shared setup for the LITE black-box e2e. Unlike the full suite, Lite has no
 * Keycloak: auth is disabled at the gateway ({@code lite} profile) and the subject
 * is passed via the {@code X-Subscriber-Id} header. The gateway is plain HTTP on
 * :8089. Base URLs override with {@code -Dvab.gateway.url=...} / {@code -Dvab.order.url=...}.
 */
abstract class LiteE2EBase {

    protected static final String GATEWAY = prop("vab.gateway.url", "http://localhost:8089");
    protected static final String ORDER   = prop("vab.order.url",   "http://localhost:8081");

    /** Generous: POST → Kafka command/reply → CDC outbox relay → saga completion. */
    protected static final Duration SETTLE = Duration.ofSeconds(60);

    private static String prop(String key, String dflt) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? dflt : v;
    }

    /** Gateway request as a given subscriber (no token in Lite — header identity). */
    protected RequestSpecification asSubscriber(String subscriberId) {
        return given().baseUri(GATEWAY).header("X-Subscriber-Id", subscriberId);
    }

    @BeforeAll
    static void requireLiveStack() {
        try {
            int code = given().baseUri(ORDER).get("/actuator/health").statusCode();
            Assumptions.assumeTrue(code == 200,
                    "lite-order-service health != 200 — start docker-compose.lite.yml + the 4 services (gateway with -Dspring.profiles.active=lite) before -Pe2e");
        } catch (Exception e) {
            Assumptions.abort("Lite stack not reachable at " + ORDER
                    + " — start infra + services + gateway. Cause: " + e.getMessage());
        }
    }

    // ── Order helpers (through the gateway, no auth) ──────────────────────────

    /** Place an order as {@code subscriberId}; asserts 202 + Location and returns the new orderId. */
    protected String placeOrder(String subscriberId, String offerCode, String productType,
                                long amount, String billingMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offerCode", offerCode);
        body.put("productType", productType);
        body.put("priceSnapshotId", "ps-lite-e2e");
        body.put("amount", amount);
        body.put("currency", "INR");
        body.put("billingMode", billingMode);

        return asSubscriber(subscriberId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(JSON)
                .body(body)
                .when().post("/v1/orders")
                .then().statusCode(202)
                .header("Location", org.hamcrest.Matchers.containsString("/v1/orders/"))
                .extract().path("orderId");
    }

    protected JsonPath getOrder(String orderId) {
        return asSubscriber("sub-premium")
                .when().get("/v1/orders/{id}", orderId)
                .then().statusCode(200)
                .extract().jsonPath();
    }

    /** Polls the read side until the order reaches exactly {@code expected}. */
    protected void awaitStatus(String orderId, String expected) {
        await().atMost(SETTLE).pollInterval(Duration.ofSeconds(3)).pollDelay(Duration.ZERO)
                .untilAsserted(() ->
                        assertThat(getOrder(orderId).getString("status")).isEqualTo(expected));
    }
}
