package com.vab.e2e;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.equalTo;

import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * DD-27 provisioning-failure park + admin re-drive. A DIGITAL_SUBSCRIPTION whose
 * OTT provisioning fails (503 OTTDOWN / 422 OTTBAD) parks in the NON-terminal
 * FULFILMENT_FAILED state (the charge stands). An admin can re-drive it
 * (retry-fulfilment) or complete it out-of-band (complete-fulfilment). Both
 * admin ops are 409 once the order is no longer parked.
 */
class OrderFulfilmentFailedE2E extends E2EBase {

	 @Test
	 @Disabled
	    void ott_real_404_order_parks_in_fulfilment_failed() {
	        String orderId = placeOrder("sub-e2e", "ott_real_404", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
	        awaitStatus(orderId, "FULFILMENT_FAILED");
	        
	        given().baseUri(ORDER)
            .when().post("/v1/orders/{id}/retry-fulfilment", orderId)
            .then().statusCode(202);
	        awaitStatus(orderId, "COMPLETED");
	    }
	 
	 
    @Test
    void ottdown_order_parks_in_fulfilment_failed() {
        String orderId = placeOrder("sub-e2e", "OTT_OTTDOWN_1M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "FULFILMENT_FAILED");
    }

    @Test
    void ottbad_order_parks_in_fulfilment_failed() {
        String orderId = placeOrder("sub-e2e", "OTT_OTTBAD_1M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "FULFILMENT_FAILED");
    }

    @Test
    void retry_fulfilment_re_parks_while_provider_still_down() {
        String orderId = placeOrder("sub-e2e", "OTT_OTTDOWN_1M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "FULFILMENT_FAILED");

        // Provider is still down → accepted, then re-parks.
        given().baseUri(ORDER)
                .when().post("/v1/orders/{id}/retry-fulfilment", orderId)
                .then().statusCode(202);
        awaitStatus(orderId, "FULFILMENT_FAILED");
    }

    @Test
    void complete_fulfilment_manual_override_completes() {
        String orderId = placeOrder("sub-e2e", "OTT_OTTBAD_1M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "FULFILMENT_FAILED");

        given().baseUri(ORDER).contentType(JSON)
                .body(Map.of("externalRef", "OTT-MANUAL-E2E"))
                .when().post("/v1/orders/{id}/complete-fulfilment", orderId)
                .then().statusCode(202);

        awaitStatus(orderId, "COMPLETED");
        given().baseUri(ORDER).get("/v1/orders/{id}", orderId)
                .then().statusCode(200)
                .body("fulfilment.externalRef", equalTo("OTT-MANUAL-E2E"));
    }

    @Test
    void retry_fulfilment_on_non_parked_is_409() {
        String orderId = placeOrder("sub-e2e", "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "COMPLETED");

        given().baseUri(ORDER)
                .when().post("/v1/orders/{id}/retry-fulfilment", orderId)
                .then().statusCode(409);
    }

    @Test
    void complete_fulfilment_on_non_parked_is_409() {
        String orderId = placeOrder("sub-e2e", "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 499, "PAY_NOW");
        awaitStatus(orderId, "COMPLETED");

        given().baseUri(ORDER).contentType(JSON)
                .body(Map.of("externalRef", "OTT-NOPE"))
                .when().post("/v1/orders/{id}/complete-fulfilment", orderId)
                .then().statusCode(409);//.log().all();
    }
}
