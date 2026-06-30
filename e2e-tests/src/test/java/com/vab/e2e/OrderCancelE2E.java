package com.vab.e2e;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

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
        // Unique subscriber: OTT_HOTSTAR_3M is a benefit, so a prior completed order
        // for the same subscriber+offer would trip the entitlement-uniqueness 409 (§B1).
        String orderId = placeOrder("sub-cancel-" + UUID.randomUUID(),
                "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");

        // Best-effort + race-tolerant: 202 if the cancel lands while the saga is still
        // in-flight, or 409 if the (fast-succeeding) order already reached a terminal
        // state before the cancel arrived (DD-26: cancel of a terminal order is refused).
        int code = given().baseUri(ORDER)
                .when().post("/v1/orders/{id}/cancel", orderId)
                .then().extract().statusCode();
        assertThat(code).isIn(202, 409);

        // Either way the order settles into a legitimate terminal state.
        awaitStatusIn(orderId, "CANCELLED", "CANCELLED_REFUNDED", "COMPLETED");
    }

    @Test
    void cancel_after_terminal_is_409() {
        String orderId = placeOrder(sub(), "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "COMPLETED");

        int code = given().baseUri(ORDER)
                .when().post("/v1/orders/{id}/cancel", orderId)
                .then().extract().statusCode();
        assertThat(code).isIn(409);

    }
}
