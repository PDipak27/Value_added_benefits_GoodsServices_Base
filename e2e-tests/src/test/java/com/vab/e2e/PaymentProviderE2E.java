package com.vab.e2e;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * payment-sim as the live stack sees it (Design/15): its merchant API needs billing's API key, its checkout
 * page needs the intent's client secret, and a PAY_NOW order shows a link to that page while PENDING_PAYMENT.
 */
class PaymentProviderE2E extends E2EBase {

    private static final String PAYMENT = System.getProperty("vab.payment.url", "http://localhost:8091");

    @Test
    void merchant_api_without_the_api_key_is_401() {
        given().baseUri(PAYMENT).contentType(JSON).header("Idempotency-Key", "e2e-no-key")
                .body("{\"orderId\":\"ord-e2e\",\"amount\":100,\"currency\":\"INR\"}")
                .when().post("/v1/payment_intents")
                .then().statusCode(401);
    }

    @Test
    void pending_order_shows_a_checkout_page_guarded_by_its_client_secret() {
        String orderId = placeUnpaidOrder(sub(), "ACC_CHARGER_30W", "PHYSICAL_GOOD");
        URI checkout = URI.create(awaitCheckoutUrl(orderId));
        assertThat(getOrder(orderId).getString("status")).isEqualTo("PENDING_PAYMENT");

        given().baseUri(checkout.getScheme() + "://" + checkout.getAuthority())
                .when().get(checkout.getPath() + "?" + checkout.getQuery())
                .then().statusCode(200).body(containsString("Pay VA-BAGS"));
        given().baseUri(checkout.getScheme() + "://" + checkout.getAuthority())
                .when().get(checkout.getPath() + "?cs=cs_wrong")
                .then().statusCode(404);

        pay(orderId);
        awaitStatus(orderId, "COMPLETED");
    }
}
