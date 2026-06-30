package com.vab.e2e;

import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
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
 *
 * <p>Since §A-1 the endpoint is a resource server, so each direct call carries a
 * Keycloak client-credentials Bearer (scope {@code ott:provision}).
 */
class OttE2E extends E2EBase {

    private String token;

    @BeforeEach
    void obtainToken() {
        token = clientCredentialsToken();
    }

    /** Authenticated request spec to the OTT provider. */
    private RequestSpecification ott() {
        return given().baseUri(OTT).header("Authorization", "Bearer " + token).contentType(JSON);
    }

    @Test
    void provision_returns_201_with_external_ref() {
        String orderId = "ord-" + UUID.randomUUID();
        ott().body(provision(orderId, "OTT_NETFLIX_6M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(201)
                .body("externalRef", startsWith("OTT-"))
                .body("status", notNullValue());
    }

    @Test
    void provision_is_idempotent_on_order_id() {
        String orderId = "ord-" + UUID.randomUUID();
        String ref1 = ott().body(provision(orderId, "OTT_NETFLIX_6M"))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(201).extract().path("externalRef");

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
                "offerCode", offerCode);
    }
}
