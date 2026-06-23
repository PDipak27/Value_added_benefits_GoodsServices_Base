package com.vab.e2e;

import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Cooperative best-effort cancel (DD-26). A cancel of an in-flight order is
 * accepted (202) and the saga resolves it at its next checkpoint — rollback to
 * CANCELLED before the pivot, forward-recovery to CANCELLED_REFUNDED after it,
 * or (lost the race) the order simply COMPLETEs. Cancelling a terminal order is
 * refused with 409.
 */
class OrderCancelE2E extends E2EBase {

    @Test
    void cancel_in_flight_is_accepted_and_resolves() {
        String orderId = placeOrder("sub-e2e", "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");

        // Best-effort: accepted regardless of how far the saga has progressed.
        given().baseUri(ORDER)
                .when().post("/v1/orders/{id}/cancel", orderId)
                .then().statusCode(202);

        // Race-dependent terminal outcome — accept any legitimate resolution.
        awaitStatusIn(orderId, "CANCELLED", "CANCELLED_REFUNDED", "COMPLETED");
    }

    @Test
    void cancel_after_terminal_is_409() {
        String orderId = placeOrder("sub-e2e", "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "COMPLETED");

        given().baseUri(ORDER)
                .when().post("/v1/orders/{id}/cancel", orderId)
                .then().statusCode(409);
    }
}
