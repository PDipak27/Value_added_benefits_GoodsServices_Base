package com.vab.e2e;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.*;

/**
 * catalog-service REST surface: eligibility-filtered list, offer detail
 * (404 for withdrawn/absent), rule-level evaluate, and the admin
 * create/update/withdraw lifecycle.
 */
class CatalogE2E extends E2EBase {

    @Test
    void list_returns_published_offers() {
        given().baseUri(CATALOG)
                .when().get("/v1/offers")
                .then().statusCode(200)
                .body("offerCode", hasItem("OTT_NETFLIX_6M"))
                .body("offerCode", not(hasItem("OTT_LEGACY_3M"))); // withdrawn
    }

    @Test
    void detail_of_published_offer() {
        given().baseUri(CATALOG)
                .when().get("/v1/offers/{code}", "OTT_NETFLIX_6M")
                .then().statusCode(200)
                .body("productType", equalTo("DIGITAL_SUBSCRIPTION"))
                .body("priceSnapshotId", notNullValue());
    }

    @Test
    void detail_of_withdrawn_offer_is_404() {
        given().baseUri(CATALOG)
                .when().get("/v1/offers/{code}", "OTT_LEGACY_3M")
                .then().statusCode(404);
    }

    @Test
    void detail_of_unknown_offer_is_404() {
        given().baseUri(CATALOG)
                .when().get("/v1/offers/{code}", "NOPE_DOES_NOT_EXIST")
                .then().statusCode(404);
    }

    @Test
    void evaluate_returns_rule_breakdown() {
        Map<String, Object> profile = Map.of(
                "planTier", "PREMIUM", "region", "IN",
                "deviceAgeMonths", 12, "kycLevel", "FULL");

        given().baseUri(CATALOG).contentType(JSON).body(profile)
                .when().post("/v1/offers/{code}:evaluate", "ACC_BUDS_PRO")
                .then().statusCode(200)
                .body("offerCode", equalTo("ACC_BUDS_PRO"))
                .body("eligible", notNullValue())
                .body("rules", notNullValue()).log().all();
    }

    @Test
    void admin_create_update_withdraw_lifecycle() {
        String code = "E2E_TMP_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Map<String, Object> body = offerBody(code, 299);

        // create → published, readable
        given().baseUri(CATALOG).contentType(JSON).body(body)
                .when().post("/v1/offers")
                .then().statusCode(201).body("offerCode", equalTo(code));
        given().baseUri(CATALOG).get("/v1/offers/{code}", code).then().statusCode(200);

        // update price
        given().baseUri(CATALOG).contentType(JSON).body(offerBody(code, 349))
                .when().put("/v1/offers/{code}", code)
                .then().statusCode(200).body("amount", equalTo(349));

        // withdraw → no longer served as detail
        given().baseUri(CATALOG)
                .when().post("/v1/offers/{code}:withdraw", code)
                .then().statusCode(200);
        given().baseUri(CATALOG).get("/v1/offers/{code}", code).then().statusCode(404);
    }

    @Test
    void withdraw_unknown_offer_is_404() {
        given().baseUri(CATALOG)
                .when().post("/v1/offers/{code}:withdraw", "NOPE_DOES_NOT_EXIST")
                .then().statusCode(404);
    }

    private static Map<String, Object> offerBody(String code, int amount) {
        return Map.of(
                "offerCode", code,
                "name", "E2E throwaway offer",
                "description", "created by CatalogE2E",
                "productType", "DIGITAL_SUBSCRIPTION",
                "amount", amount,
                "currency", "INR",
                "priceSnapshotId", "ps-e2e-" + amount,
                "minPlanTier", "BASIC",
                "allowedRegions", "IN",
                "minKycLevel", "MINIMAL");
    }
}
