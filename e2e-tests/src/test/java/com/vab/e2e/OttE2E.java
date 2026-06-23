package com.vab.e2e;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.*;

/**
 * ott-service provisioning API (DD-27), driven directly: create (201),
 * idempotent repeat on orderId (200, same externalRef), and the two demo
 * failure triggers carried in offerCode — OTTDOWN → 503, OTTBAD → 422.
 */
class OttE2E extends E2EBase {

    @Test
    void provision_returns_201_with_external_ref() {
        String orderId = "ord-" + UUID.randomUUID();
        given().baseUri(OTT).contentType(JSON)
                .body(provision(orderId, "OTT_NETFLIX_6M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(201)
                .body("externalRef", startsWith("OTT-"))
                .body("status", notNullValue());
    }

    @Test
    void provision_is_idempotent_on_order_id() {
        String orderId = "ord-" + UUID.randomUUID();
        String ref1 = given().baseUri(OTT).contentType(JSON)
                .body(provision(orderId, "OTT_NETFLIX_6M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(201).extract().path("externalRef");

        // Same orderId → existing entitlement returned (200), not a duplicate.
        given().baseUri(OTT).contentType(JSON)
                .body(provision(orderId, "OTT_NETFLIX_6M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(200)
                .body("externalRef", equalTo(ref1));
    }

    @Test
    void provision_ottdown_returns_503() {
        given().baseUri(OTT).contentType(JSON)
                .body(provision("ord-" + UUID.randomUUID(), "OTT_OTTDOWN_1M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(503);
    }

    @Test
    void provision_ottbad_returns_422() {
        given().baseUri(OTT).contentType(JSON)
                .body(provision("ord-" + UUID.randomUUID(), "OTT_OTTBAD_1M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(422);
    }

    private static Map<String, Object> provision(String orderId, String offerCode) {
        return Map.of(
                "orderId", orderId,
                "subscriberId", "sub-e2e",
                "offerCode", offerCode);
    }
}
