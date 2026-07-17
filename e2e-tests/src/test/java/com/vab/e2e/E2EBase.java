package com.vab.e2e;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Shared setup for the black-box E2E suite. Since §A-3, the subscriber-facing REST
 * surface is behind the gateway (:8089) and requires a Keycloak bearer token, so the
 * order helpers here authenticate: they mint a token whose {@code subscriberId} claim
 * is the desired subject (creating the Keycloak user on demand via the Admin API), and
 * call through the gateway. Base URLs can be overridden with {@code -Dvab.<svc>.url=...}.
 */
abstract class E2EBase {

    // §E2 edge TLS: the gateway is HTTPS (mkcert). §4: Keycloak defaults to HTTP dev mode —
    // override with -Dvab.keycloak.url=https://localhost:8088 when running the TLS setup.
    protected static final String GATEWAY  = prop("vab.gateway.url",  "https://localhost:8089");
    protected static final String ORDER    = prop("vab.order.url",    "http://localhost:8081");
    protected static final String CATALOG  = prop("vab.catalog.url",  "http://localhost:8085");
    protected static final String OTT       = prop("vab.ott.url",      "http://localhost:8087");
    protected static final String KEYCLOAK  = prop("vab.keycloak.url", "http://localhost:8088");

    /** Generous: the async path is POST → Kafka command/reply → CDC relay → projection. */
    protected static final Duration SETTLE = Duration.ofSeconds(45);

    private static final String E2E_PASSWORD = "e2e-pass";
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    private static String prop(String key, String dflt) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? dflt : v;
    }

    /** A Keycloak client-credentials access token for the provisioning client (§A-1). */
    protected String clientCredentialsToken() {
        return given().baseUri(KEYCLOAK)
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", "vab-provisioning")
                .formParam("client_secret", "vab-provisioning-secret")
                .formParam("scope", "ott:provision")
                .when().post("/realms/vab/protocol/openid-connect/token")
                .then().statusCode(200)
                .extract().path("access_token");
    }

    protected String sub() {
        return "sub-rev-" + UUID.randomUUID();
    }

    // ── §A-3/A5 identity: tokens for gateway / order-service calls ────────────

    /** A user token whose {@code subscriberId} claim == the argument (user created on demand). */
    protected static String tokenForSubscriber(String subscriberId) {
        return TOKENS.computeIfAbsent("sub:" + subscriberId, k -> {
            ensureUser(subscriberId, subscriberId);
            return passwordToken(subscriberId, E2E_PASSWORD);
        });
    }

    /** The seeded back-office admin token (carries the {@code vab-admin} realm role). */
    protected static String adminToken() {
        return TOKENS.computeIfAbsent("admin", k -> passwordToken("vabadmin", "vabadmin"));
    }

    /** Request through the gateway as a specific subscriber (place / my-orders / my-benefits). */
    protected RequestSpecification asSubscriber(String subscriberId) {
        return given().baseUri(GATEWAY).header("Authorization", "Bearer " + tokenForSubscriber(subscriberId));
    }

    /** Request through the gateway as the back-office admin (retry / complete / revoke / ops). */
    protected RequestSpecification asAdmin() {
        return given().baseUri(GATEWAY).header("Authorization", "Bearer " + adminToken());
    }

    /**
     * Reads / cancels of an <em>arbitrary</em> order where the test doesn't thread the
     * owner (e.g. status polling). Uses the admin token, which bypasses the §A-3
     * object-level ownership check — ownership itself is asserted with real subscriber
     * tokens in {@code GatewayAuthE2E}.
     */
    protected RequestSpecification authed() {
        return asAdmin();
    }

    private static String kcAdminToken() {
        return given().baseUri(KEYCLOAK)
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password").formParam("client_id", "admin-cli")
                .formParam("username", "admin").formParam("password", "admin")
                .when().post("/realms/master/protocol/openid-connect/token")
                .then().statusCode(200).extract().path("access_token");
    }

    private static volatile boolean userProfileReady = false;

    /**
     * Keycloak 24+ turns on the declarative user profile and, by default, REJECTS
     * undeclared custom attributes with 400 — so a user POST carrying {@code subscriberId}
     * fails outright. Declare it (and allow unmanaged attributes) once before creating users.
     */
    private static synchronized void ensureUserProfileAllowsSubscriberId() {
        if (userProfileReady) return;
        Map<String, Object> perms = Map.of("view", List.of("admin", "user"), "edit", List.of("admin", "user"));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("unmanagedAttributePolicy", "ENABLED");
        profile.put("attributes", List.of(
                Map.of("name", "username", "permissions", perms),
                Map.of("name", "email", "permissions", perms),
                Map.of("name", "subscriberId", "displayName", "Subscriber ID", "permissions", perms)));
        int sc = given().baseUri(KEYCLOAK).header("Authorization", "Bearer " + kcAdminToken())
                .contentType(JSON).body(profile)
                .when().put("/admin/realms/vab/users/profile")
                .getStatusCode();
        if (sc != 200 && sc != 204) {
            throw new IllegalStateException("Failed to configure Keycloak user profile: HTTP " + sc);
        }
        userProfileReady = true;
    }

    /** Create a Keycloak user (username == subscriberId) carrying the subscriberId attribute; 409 if it exists. */
    private static void ensureUser(String subscriberId, String username) {
        ensureUserProfileAllowsSubscriberId();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("enabled", true);
        user.put("emailVerified", true);
        user.put("attributes", Map.of("subscriberId", List.of(subscriberId)));
        user.put("credentials", List.of(Map.of("type", "password", "value", E2E_PASSWORD, "temporary", false)));
        Response r = given().baseUri(KEYCLOAK).header("Authorization", "Bearer " + kcAdminToken())
                .contentType(JSON).body(user)
                .when().post("/admin/realms/vab/users");
        if (r.getStatusCode() != 201 && r.getStatusCode() != 409) {   // created / already exists
            throw new IllegalStateException("Keycloak user create failed for " + username
                    + ": HTTP " + r.getStatusCode() + " — " + r.asString());
        }
    }

    private static String passwordToken(String username, String password) {
        return given().baseUri(KEYCLOAK)
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password").formParam("client_id", "vab-ott")
                .formParam("username", username).formParam("password", password)
                .formParam("scope", "openid")
                .when().post("/realms/vab/protocol/openid-connect/token")
                .then().statusCode(200).extract().path("access_token");
    }

    @BeforeAll
    static void requireLiveStack() {
        // §E2: trust the mkcert (self-signed-ish) certs on the HTTPS hops without
        // wiring a truststore into the test JVM.
        io.restassured.RestAssured.useRelaxedHTTPSValidation();
        try {
            int code = given().baseUri(ORDER).get("/actuator/health").statusCode();
            Assumptions.assumeTrue(code == 200,
                    "order-service health != 200 — start the stack (docker-compose up + the services + gateway) before -Pe2e");
        } catch (Exception e) {
            Assumptions.abort("Stack not reachable at " + ORDER
                    + " — start docker-compose + all services + gateway. Cause: " + e.getMessage());
        }
    }

    // ── Order helpers (through the gateway, authenticated) ───────────────────

    /** Place an order as {@code subscriberId}; asserts 202 + Location and returns the new orderId. */
    protected String placeOrder(String subscriberId, String offerCode, String productType,
                                long amount, String billingMode) {
        return placeOrder(subscriberId, offerCode, productType, amount, billingMode,
                UUID.randomUUID().toString());
    }

    protected String placeOrder(String subscriberId, String offerCode, String productType,
                                long amount, String billingMode, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offerCode", offerCode);
        body.put("productType", productType);
        body.put("priceSnapshotId", "ps-e2e");
        body.put("amount", amount);
        body.put("currency", "INR");
        body.put("billingMode", billingMode);

        return asSubscriber(subscriberId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(JSON)
                .body(body)
                .when().post("/v1/orders")
                .then().statusCode(202)
                .header("Location", org.hamcrest.Matchers.containsString("/v1/orders/"))
                .extract().path("orderId");
    }

    protected JsonPath getOrder(String orderId) {
        return authed()
                .when().get("/v1/orders/{id}", orderId)
                .then().statusCode(200)
                .extract().jsonPath();
    }

    protected Response getOrderRaw(String orderId) {
        return authed().when().get("/v1/orders/{id}", orderId);
    }

    /** Polls the query side until the order reaches exactly {@code expected}. */
    protected void awaitStatus(String orderId, String expected) {
        await().atMost(SETTLE).pollInterval(Duration.ofSeconds(5)).pollDelay(Duration.ZERO)
                .untilAsserted(() ->
                        assertThat(getOrder(orderId).getString("status")).isEqualTo(expected));
    }

    /** Polls until the order reaches any one of the acceptable (e.g. race-dependent) states. */
    protected void awaitStatusIn(String orderId, String... acceptable) {
        await().atMost(SETTLE).pollInterval(Duration.ofSeconds(5)).pollDelay(Duration.ZERO)
                .untilAsserted(() ->
                        assertThat(getOrder(orderId).getString("status")).isIn((Object[]) acceptable));
    }

    protected String currentStatus(String orderId) {
        return getOrder(orderId).getString("status");
    }
}
