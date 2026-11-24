package com.vab.e2e;

import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * ott-service partner API (Design/06 §8), driven directly with the partner API key: a new request is accepted
 * (202 PENDING), OTT activates it moments later (a repeat then shows ACTIVE), repeats are idempotent on orderId,
 * and the synchronous demo triggers in offerCode: OTTDOWN → 503, OTTBAD → 422.
 */
class OttE2E extends E2EBase {

    private RequestSpecification ott() {
        return given().baseUri(OTT).header("X-Api-Key", OTT_API_KEY).contentType(JSON);
    }

    @Test
    void provision_is_accepted_as_pending_then_activated() {
        String orderId = "ord-" + UUID.randomUUID();
        ott().body(provision(orderId, "OTT_NETFLIX_6M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(202)
                .body("externalRef", startsWith("OTT-"))
                .body("status", equalTo("PENDING"));

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                ott().body(provision(orderId, "OTT_NETFLIX_6M"))
                        .when().post("/ott/v1/entitlements")
                        .then().statusCode(200)
                        .body("status", equalTo("ACTIVE"))
                        .body("validFrom", notNullValue()));
    }

    @Test
    void provision_is_idempotent_on_order_id() {
        String orderId = "ord-" + UUID.randomUUID();
        String ref1 = ott().body(provision(orderId, "OTT_NETFLIX_6M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(202).extract().path("externalRef");

        // Same orderId → existing entitlement returned (200), not a duplicate.
        ott().body(provision(orderId, "OTT_NETFLIX_6M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(200)
                .body("externalRef", equalTo(ref1));
    }

    @Test
    void provision_ottdown_returns_503() {
        ott().body(provision("ord-" + UUID.randomUUID(), "OTT_OTTDOWN_1M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(503);
    }

    @Test
    void provision_ottbad_returns_422() {
        ott().body(provision("ord-" + UUID.randomUUID(), "OTT_OTTBAD_1M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(422);
    }

    private Map<String, Object> provision(String orderId, String offerCode) {
        return Map.of(
                "orderId", orderId,
                "subscriberId", sub(),
                "offerCode", offerCode,
                "validFrom", "2026-11-20T10:00:00Z");
    }
}
