package com.vab.e2e;

import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Order saga happy paths to COMPLETED, one per product type plus a
 * BILL_TO_MOBILE flow. Each asserts the terminal status on the query side and
 * that exactly the per-type fulfilment artifact is populated (PHYSICAL_GOOD →
 * trackingRef, SOFTWARE_LICENSE → activationKey, DIGITAL_SUBSCRIPTION →
 * externalRef). Amounts are kept ≤ 999 to clear the billing demo thresholds.
 */
class OrderHappyPathE2E extends E2EBase {

    @Test
    void physical_good_pay_now_completes_with_tracking_ref() {
        String orderId = placeOrder(sub(), "ACC_BUDS_PRO", "PHYSICAL_GOOD", 499, "PAY_NOW");
        awaitStatus(orderId, "COMPLETED");

        given().baseUri(ORDER).get("/v1/orders/{id}", orderId)
                .then().statusCode(200)
                .body("fulfilment.trackingRef", notNullValue())
                .body("fulfilment.activationKey", nullValue())
                .body("fulfilment.externalRef", nullValue());
    }

    @Test
    void software_license_pay_now_completes_with_activation_key() {
        String orderId = placeOrder(sub(), "SW_MSOFFICE_1Y", "SOFTWARE_LICENSE", 499, "PAY_NOW");
        awaitStatus(orderId, "COMPLETED");

        given().baseUri(ORDER).get("/v1/orders/{id}", orderId)
                .then().statusCode(200)
                .body("fulfilment.activationKey", notNullValue())
                .body("fulfilment.trackingRef", nullValue())
                .body("fulfilment.externalRef", nullValue());
    }

    @Test
    void digital_subscription_pay_now_completes_with_external_ref() {
        String orderId = placeOrder(sub(), "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "COMPLETED");

        given().baseUri(ORDER).get("/v1/orders/{id}", orderId)
                .then().statusCode(200)
                .body("fulfilment.externalRef", startsWith("OTT-"))
                .body("fulfilment.trackingRef", nullValue())
                .body("fulfilment.activationKey", nullValue());
    }

    @Test
    void digital_subscription_bill_to_mobile_completes() {
        String orderId = placeOrder(sub(), "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "BILL_TO_MOBILE");
        awaitStatus(orderId, "COMPLETED");

        given().baseUri(ORDER).get("/v1/orders/{id}", orderId)
                .then().statusCode(200)
                .body("fulfilment.externalRef", startsWith("OTT-"));
    }
}
