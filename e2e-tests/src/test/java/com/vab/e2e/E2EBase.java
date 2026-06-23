package com.vab.e2e;

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
import io.restassured.response.Response;

/**
 * Shared setup for the black-box E2E suite. Base URLs default to the local ports
 * each service binds to (see each application.yml) and can be overridden with
 * {@code -Dvab.<svc>.url=...}. The whole suite is skipped (JUnit assumption) when
 * order-service is unreachable, so running without a live stack reports "skipped"
 * rather than a wall of connection failures.
 */
abstract class E2EBase {

    protected static final String ORDER   = prop("vab.order.url",   "http://localhost:8081");
    protected static final String CATALOG = prop("vab.catalog.url", "http://localhost:8085");
    protected static final String OTT     = prop("vab.ott.url",     "http://localhost:8087");

    /** Generous: the async path is POST → Kafka command/reply → CDC relay → projection. */
    protected static final Duration SETTLE = Duration.ofSeconds(45);

    private static String prop(String key, String dflt) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? dflt : v;
    }

    @BeforeAll
    static void requireLiveStack() {
        try {
            int code = given().baseUri(ORDER).get("/actuator/health").statusCode();
            Assumptions.assumeTrue(code == 200,
                    "order-service health != 200 — start the stack (docker-compose up + the services) before -Pe2e");
        } catch (Exception e) {
            Assumptions.abort("Stack not reachable at " + ORDER
                    + " — start docker-compose + all services. Cause: " + e.getMessage());
        }
    }

    // ── Order helpers ───────────────────────────────────────────────────────

    /** Place an order; asserts 202 + Location and returns the new orderId. */
    protected String placeOrder(String subscriberId, String offerCode, String productType,
                                long amount, String billingMode) {
        return placeOrder(subscriberId, offerCode, productType, amount, billingMode,
                UUID.randomUUID().toString());
    }

    protected String placeOrder(String subscriberId, String offerCode, String productType,
                                long amount, String billingMode, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subscriberId", subscriberId);
        body.put("offerCode", offerCode);
        body.put("productType", productType);
        body.put("priceSnapshotId", "ps-e2e");
        body.put("amount", amount);
        body.put("currency", "INR");
        body.put("billingMode", billingMode);

        return given().baseUri(ORDER)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(JSON)
                .body(body)
                .when().post("/v1/orders")
                .then().statusCode(202)
                .header("Location", org.hamcrest.Matchers.containsString("/v1/orders/"))
                .extract().path("orderId");
    }

    protected JsonPath getOrder(String orderId) {
        return given().baseUri(ORDER)
                .when().get("/v1/orders/{id}", orderId)
                .then().statusCode(200)
                .extract().jsonPath();
    }

    protected Response getOrderRaw(String orderId) {
        return given().baseUri(ORDER).when().get("/v1/orders/{id}", orderId);
    }

    /** Polls the query side until the order reaches exactly {@code expected}. */
    protected void awaitStatus(String orderId, String expected) {
        await().atMost(SETTLE).pollInterval(Duration.ofSeconds(5)).pollDelay(Duration.ZERO)
                .untilAsserted(() ->
                        assertThat(getOrder(orderId).getString("status")).isEqualTo(expected));
    }

    /** Polls until the order reaches any one of the acceptable (e.g. race-dependent) states. */
    protected void awaitStatusIn(String orderId, String... acceptable) {
        await().atMost(SETTLE).pollInterval(Duration.ofSeconds(5)).pollDelay(Duration.ZERO)
                .untilAsserted(() ->
                        assertThat(getOrder(orderId).getString("status")).isIn((Object[]) acceptable));
    }

    protected String currentStatus(String orderId) {
        return getOrder(orderId).getString("status");
    }
}
