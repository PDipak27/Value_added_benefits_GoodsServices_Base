package com.vab.e2e;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Order failure paths (DD-26). Placement-time rejections (price verified against catalog): a tampered
 * price is 409, an unknown offer is 422. Saga failures that end the order FAILED: the subscriber declines
 * on payment-sim's checkout page, payment-sim is down (ACC_DEMO_PSPDOWN, priced 503), capture hard-decline at
 * the pivot (ACC_DEMO_HARDDECLINE, priced 777 — authorisation voided, hold released, nothing captured), and
 * BILL_TO_MOBILE account-limit failures
 * (suspended account; amount over the credit limit). Post-pivot: a delivery failure forward-recovers
 * (refund + release) to CANCELLED_REFUNDED.
 */
class OrderFailurePathsE2E extends E2EBase {

    /** A client price below catalog's is rejected at placement with 409 (no order is created). */
    @Test
    void tampered_price_is_rejected_409() {
        Map<String, Object> body = orderBody("OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", "PAY_NOW");
        body.put("amount", 1);

        placeOrderRequest(sub(), body, UUID.randomUUID().toString())
                .then().statusCode(409)
                .body("detail", containsString("Price mismatch"));
    }

    /** An offer catalog doesn't publish is rejected at placement with 422 (it never reaches the saga). */
    @Test
    void unknown_offer_is_rejected_422() {
        Map<String, Object> body = orderBody("OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", "PAY_NOW");
        body.put("offerCode", "NOPE_NO_SUCH_OFFER");

        placeOrderRequest(sub(), body, UUID.randomUUID().toString()).then().statusCode(422);
    }

    /** PAY_NOW, the subscriber presses Decline on the checkout page: FAILED, inventory hold released. */
    @Test
    void declined_at_checkout_fails() {
        String orderId = placeUnpaidOrder(sub(), "ACC_CHARGER_30W", "PHYSICAL_GOOD");
        declinePayment(orderId);
        awaitStatus(orderId, "FAILED");
    }

    /** PAY_NOW while the payment provider is down (payment-sim refuses amount 503): FAILED, no checkout link ever shown. */
    @Test
    void payment_provider_down_fails_before_checkout() {
        String orderId = placeUnpaidOrder(sub(), "ACC_DEMO_PSPDOWN", "PHYSICAL_GOOD");
        awaitStatus(orderId, "FAILED");
        assertThat(getOrder(orderId).getString("payment")).isNull();
    }

    /** PAY_NOW at exactly 777 is paid but hard-declines at capture (the pivot): the order ends FAILED. */
    @Test
    void capture_hard_decline_at_pivot_fails() {
        String orderId = placeOrder(sub(), "ACC_DEMO_HARDDECLINE", "PHYSICAL_GOOD", "PAY_NOW");
        awaitStatus(orderId, "FAILED");
    }

    /** BILL_TO_MOBILE for a SUSPENDED billing account fails at the account-limit check: the order ends FAILED. */
    @Test
    void bill_to_mobile_suspended_account_fails() {
        String orderId = placeOrder("sub-suspended", "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", "BILL_TO_MOBILE");
        awaitStatus(orderId, "FAILED");
    }

    /** BILL_TO_MOBILE above the subscriber's credit limit (new account default 1000 &lt; 1500) fails: FAILED. */
    @Test
    void bill_to_mobile_over_credit_limit_fails() {
        String orderId = placeOrder(sub(), "ACC_DEMO_DECLINE", "PHYSICAL_GOOD", "BILL_TO_MOBILE");
        awaitStatus(orderId, "FAILED");
    }

    /** Post-pivot delivery failure (offer code contains FAIL): the saga forward-recovers — refund + release — to CANCELLED_REFUNDED. */
    @Test
    void delivery_failure_after_payment_forward_recovers_to_cancelled_refunded() {
        String orderId = placeOrder(sub(), "ACC_DEMO_DELIVERYFAIL", "PHYSICAL_GOOD", "PAY_NOW");
        awaitStatus(orderId, "CANCELLED_REFUNDED");
    }
}
