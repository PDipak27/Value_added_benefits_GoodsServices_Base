package com.vab.e2e;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * §B1 "My Benefits": a completed DIGITAL_SUBSCRIPTION order materialises an ACTIVE
 * entitlement carrying a validity window (Phase 2), and a re-purchase of the same
 * offer is rejected 409 by uniqueness layer 1. §A-3: benefits are the caller's own
 * (JWT subject, no query param); revoke is admin-only.
 */
class EntitlementsE2E extends E2EBase {

    @Test
    void completed_digital_appears_in_my_benefits_with_validity_and_blocks_repurchase() {
        String sub = "sub-ent-" + UUID.randomUUID();
        String orderId = placeOrder(sub, "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "COMPLETED");

        // My Benefits — entitlement projection lags the order, so poll until it shows.
        await().atMost(SETTLE).pollInterval(Duration.ofSeconds(3)).pollDelay(Duration.ZERO)
                .untilAsserted(() ->
                        asSubscriber(sub).get("/v1/entitlements")
                                .then().statusCode(200)
                                .body("offerCode", hasItem("OTT_HOTSTAR_3M")));

        // A 3-month OTT bundle has a populated validUntil.
        Object validUntil = asSubscriber(sub).get("/v1/entitlements")
                .then().statusCode(200)
                .extract().path("find { it.offerCode == 'OTT_HOTSTAR_3M' }.validUntil");
        assertThat(validUntil).isNotNull();

        // Re-purchase of the same offer → 409 (uniqueness layer 1). Subject from the token.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offerCode", "OTT_HOTSTAR_3M");
        body.put("productType", "DIGITAL_SUBSCRIPTION");
        body.put("priceSnapshotId", "ps-e2e");
        body.put("amount", 499);
        body.put("currency", "INR");
        body.put("billingMode", "PAY_NOW");
        asSubscriber(sub)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(JSON).body(body)
                .when().post("/v1/orders")
                .then().statusCode(409);
    }

    @Test
    void admin_revoke_removes_the_benefit_from_my_benefits() {
        String sub = "sub-rev-" + UUID.randomUUID();
        String orderId = placeOrder(sub, "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "COMPLETED");

        await().atMost(SETTLE).pollInterval(Duration.ofSeconds(3)).pollDelay(Duration.ZERO)
                .untilAsserted(() ->
                        asSubscriber(sub).get("/v1/entitlements")
                                .then().statusCode(200).body("offerCode", hasItem("OTT_HOTSTAR_3M")));

        // Admin revoke → 202; entitlement flips to REVOKED asynchronously.
        asAdmin()
                .when().post("/v1/orders/{id}/revoke-entitlement", orderId)
                .then().statusCode(202);

        // The benefit leaves "My Benefits" (read model no longer ACTIVE).
        await().atMost(SETTLE).pollInterval(Duration.ofSeconds(3)).pollDelay(Duration.ZERO)
                .untilAsserted(() ->
                        asSubscriber(sub).get("/v1/entitlements")
                                .then().statusCode(200)
                                .body("offerCode", not(hasItem("OTT_HOTSTAR_3M"))));
    }
}
