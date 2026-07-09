package com.vab.e2e;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;

/**
 * §A-3/A5 edge auth. The gateway validates Keycloak JWTs, relays them downstream,
 * keeps catalog browse public, and gates back-office actions on the {@code vab-admin}
 * realm role. (The subscriber happy paths through the gateway are covered by the
 * migrated order/entitlement suites; here we assert the auth boundary itself.)
 */
class GatewayAuthE2E extends E2EBase {

    private static Map<String, Object> orderBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offerCode", "OTT_HOTSTAR_3M");
        body.put("productType", "DIGITAL_SUBSCRIPTION");
        body.put("priceSnapshotId", "ps-e2e");
        body.put("amount", 499);
        body.put("currency", "INR");
        body.put("billingMode", "PAY_NOW");
        return body;
    }

    @Test
    void placing_an_order_without_a_token_is_401() {
        given().baseUri(GATEWAY).contentType(JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(orderBody())
                .when().post("/v1/orders")
                .then().statusCode(401);
    }

    @Test
    void my_orders_without_a_token_is_401() {
        given().baseUri(GATEWAY).when().get("/v1/orders")
                .then().statusCode(401);
    }

    @Test
    void catalog_browse_is_public_through_the_gateway() {
        given().baseUri(GATEWAY).when().get("/v1/offers")
                .then().statusCode(200);
    }

    @Test
    void subscriber_token_cannot_reach_admin_revoke_403() {
        // The role gate is enforced at the gateway before routing, so the order state
        // is irrelevant — a non-admin token is rejected 403 regardless.
        String sub = sub();
        String orderId = placeOrder(sub, "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        asSubscriber(sub)
                .when().post("/v1/orders/{id}/revoke-entitlement", orderId)
                .then().statusCode(403);
    }

    @Test
    void subscriber_can_read_their_own_order() {
        String sub = sub();
        String orderId = placeOrder(sub, "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        asSubscriber(sub).get("/v1/orders/{id}", orderId)
                .then().statusCode(200)
                .body("subscriberId", org.hamcrest.Matchers.equalTo(sub));
    }

    @Test
    void subscriber_cannot_read_another_subscribers_order() {
        // §A-3 object-level authorization (IDOR fix): a different subscriber gets 404
        // (not 403 / not the data) — the order's existence is not revealed.
        String owner = sub();
        String orderId = placeOrder(owner, "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        asSubscriber(sub())
                .get("/v1/orders/{id}", orderId)
                .then().statusCode(404);
    }

    @Test
    void subscriber_cannot_cancel_another_subscribers_order() {
        String owner = sub();
        String orderId = placeOrder(owner, "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        asSubscriber(sub())
                .when().post("/v1/orders/{id}/cancel", orderId)
                .then().statusCode(404);
    }

    @Test
    void admin_token_passes_the_role_gate() {
        // Admin clears the gate → reaches order-service, which returns 409 (not parked),
        // NOT 401/403 — proving the vab-admin role was accepted and the token relayed.
        String sub = sub();
        String orderId = placeOrder(sub, "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "COMPLETED");
        asAdmin()
                .when().post("/v1/orders/{id}/retry-fulfilment", orderId)
                .then().statusCode(409);
    }
}
