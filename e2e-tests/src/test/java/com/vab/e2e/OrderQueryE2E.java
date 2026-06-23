package com.vab.e2e;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/**
 * Query side (CQRS): point read by id (read-your-writes — never 404s right after
 * a POST, via the write-store fallback in DD-15), 404 for an unknown id, and the
 * list-by-subscriber projection (read model only, so it's polled until it lands).
 */
class OrderQueryE2E extends E2EBase {

    @Test
    void get_by_id_is_readable_immediately_after_place() {
        String orderId = placeOrder("sub-e2e", "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        given().baseUri(ORDER).get("/v1/orders/{id}", orderId)
                .then().statusCode(200)
                .body("orderId", equalTo(orderId))
                .body("subscriberId", equalTo("sub-e2e"))
                .body("productType", equalTo("DIGITAL_SUBSCRIPTION"))
                .body("status", notNullValue());
    }

    @Test
    void get_unknown_id_is_404() {
        given().baseUri(ORDER).get("/v1/orders/{id}", "ord-" + UUID.randomUUID())
                .then().statusCode(404);
    }

    @Test
    void list_by_subscriber_contains_placed_order() {
        String sub = "sub-e2e-" + UUID.randomUUID();
        String orderId = placeOrder(sub, "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");

        // List is served from the read model only — poll until the projection lands.
        await().atMost(SETTLE).pollInterval(Duration.ofSeconds(1)).pollDelay(Duration.ZERO)
                .untilAsserted(() -> given().baseUri(ORDER).queryParam("subscriberId", sub)
                        .when().get("/v1/orders")
                        .then().statusCode(200)
                        .body("orderId", hasItem(orderId)));
    }
}
