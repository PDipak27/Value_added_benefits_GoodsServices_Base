package com.vab.e2e;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/**
 * Design/06 §8: the OTT partner API is authenticated with the API key OTT issued to VA-BAGS ({@code X-Api-Key}).
 * No key, a wrong key, or a Keycloak client-credentials token (the old credential) is a 401. The provisioning path
 * through the saga is unaffected because fulfilment sends the key.
 */
class OttAuthE2E extends E2EBase {

    private Map<String, Object> provisionBody(String orderId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("orderId", orderId);
        b.put("subscriberId", "sub-auth");
        b.put("offerCode", "OTT_HOTSTAR_3M");
        return b;
    }

    @Test
    void no_api_key_is_401() {
        given().baseUri(OTT).contentType(JSON).body(provisionBody("ord-" + UUID.randomUUID()))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(401);
    }

    @Test
    void wrong_api_key_is_401() {
        given().baseUri(OTT).header("X-Api-Key", "not-the-key").contentType(JSON)
                .body(provisionBody("ord-" + UUID.randomUUID()))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(401);
    }

    @Test
    void a_keycloak_bearer_token_is_no_longer_accepted() {
        given().baseUri(OTT).header("Authorization", "Bearer " + clientCredentialsToken()).contentType(JSON)
                .body(provisionBody("ord-" + UUID.randomUUID()))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(401);
    }

    @Test
    void the_partner_api_key_is_accepted() {
        given().baseUri(OTT).header("X-Api-Key", OTT_API_KEY)
                .contentType(JSON).body(provisionBody("ord-auth-" + UUID.randomUUID()))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(anyOf(is(202), is(200)));   // 202 new / 200 idempotent
    }
}
