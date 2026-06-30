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
 * §A-1 / DD-29: ott-service is an OAuth2 resource server. Provisioning requires a
 * valid Keycloak client-credentials token carrying scope {@code ott:provision} —
 * without it, the resource server returns 401. The happy provisioning path through
 * the saga is unchanged because fulfilment now attaches the token automatically.
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
    void unauthenticated_provision_is_401() {
        given().baseUri(OTT).contentType(JSON).body(provisionBody("ord-" + UUID.randomUUID()))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(401);
    }

    @Test
    void authenticated_provision_succeeds() {
        String token = clientCredentialsToken();
        given().baseUri(OTT).header("Authorization", "Bearer " + token)
                .contentType(JSON).body(provisionBody("ord-auth-" + UUID.randomUUID()))
                .when().post("/ott/v1/entitlements")
                .then().statusCode(anyOf(is(201), is(200))).log().all();   // 201 new / 200 idempotent
    }
}
