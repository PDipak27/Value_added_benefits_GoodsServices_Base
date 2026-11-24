package com.vab.e2e;

import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * DIGITAL_SUBSCRIPTION provisioning failures (Design/13 §6) through the live stack. OTTBAD (422) is refunded
 * at once; OTTDOWN (503) keeps the order CONFIRMED while fulfilment retries, and a user cancel during that
 * wait is refunded. (The 2 h deadline itself is covered by FulfilmentProvisioningIT.)
 */
class ProvisioningFailureE2E extends E2EBase {

    @Test
    void ottbad_order_is_refunded() {
        String orderId = placeOrder(sub(), "OTT_OTTBAD_1M", "DIGITAL_SUBSCRIPTION", "PAY_NOW");
        awaitStatus(orderId, "CANCELLED_REFUNDED");
    }

    @Test
    void ottdown_order_waits_confirmed_and_a_cancel_refunds_it() {
        String subscriber = "sub-ottdown-" + UUID.randomUUID();
        String orderId = placeOrder(subscriber, "OTT_OTTDOWN_1M", "DIGITAL_SUBSCRIPTION", "PAY_NOW");
        awaitStatus(orderId, "CONFIRMED");

        asSubscriber(subscriber)
                .when().post("/v1/orders/{id}/cancel", orderId)
                .then().statusCode(202);

        awaitStatus(orderId, "CANCELLED_REFUNDED");
    }
}
