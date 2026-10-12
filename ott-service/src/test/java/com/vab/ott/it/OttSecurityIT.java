package com.vab.ott.it;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.vab.ott.domain.Entitlement;
import com.vab.ott.domain.EntitlementRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ott-service security on real infra (§A-1/A-2, DD-27/29). The provisioning API is exercised with REAL
 * RS256 tokens verified by the app's own {@code JwtDecoder} against a stub JWKS endpoint, so signature,
 * issuer, expiry, the {@code ott-service} audience and the {@code ott:provision} scope are all checked
 * for real. The video surface uses {@code oidcLogin()} to test the entitlement gate on {@code /stream}.
 *
 * <p>Runs with the {@code eks} profile: its split-horizon config uses explicit Keycloak endpoints
 * (pointed at the stub) instead of boot-time OIDC discovery — no Keycloak needed.
 */
@SpringBootTest(properties = "KEYCLOAK_ISSUER=" + OttSecurityIT.ISSUER)
@AutoConfigureMockMvc
@ActiveProfiles("eks")
class OttSecurityIT {

    static final String ISSUER = "http://issuer.test/realms/vab";
    private static final String API = "/ott/v1/entitlements";

    static final RSAKey SIGNING_KEY = rsaKey("k1");
    static final RSAKey FOREIGN_KEY = rsaKey("k1");   // same kid, different key → bad signature
    static final HttpServer keycloakStub = startJwksStub();

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
                    .withDatabaseName("vab").withUsername("eventuate").withPassword("eventuate").withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String stub = "http://localhost:" + keycloakStub.getAddress().getPort();
        r.add("KEYCLOAK_PUBLIC_URL", () -> stub);
        r.add("KEYCLOAK_INTERNAL_URL", () -> stub);
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopStub() {
        keycloakStub.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired EntitlementRepository entitlements;

    // ── token minting ───────────────────────────────────────────────────────

    /** A provisioning token as Keycloak would mint it for vab-provisioning; each parameter can be broken by a test. */
    private static String token(String audience, String scope, String issuer, Instant expiresAt, RSAKey signer) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("service-account-vab-provisioning")
                .claim("scope", scope)
                .issueTime(new Date())
                .expirationTime(Date.from(expiresAt));
        if (audience != null) claims.audience(audience);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(signer));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String validToken() {
        return token("ott-service", "ott:provision", ISSUER, Instant.now().plusSeconds(300), SIGNING_KEY);
    }

    private ResultActions provision(String bearer, String orderId, String subscriberId, String offerCode) throws Exception {
        var req = post(API).contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId":"%s","subscriberId":"%s","offerCode":"%s"}""".formatted(orderId, subscriberId, offerCode));
        if (bearer != null) req.header("Authorization", "Bearer " + bearer);
        return mvc.perform(req);
    }

    private static String newOrderId() {
        return "ord_it_" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── provisioning API: resource server (chain 1) ─────────────────────────

    /** A correctly signed token with aud=ott-service and scope ott:provision provisions an ACTIVE entitlement (201). */
    @Test
    void valid_provisioning_token_creates_entitlement() throws Exception {
        String orderId = newOrderId();

        provision(validToken(), orderId, "sub-it", "OTT_NETFLIX_6M")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalRef").value(org.hamcrest.Matchers.startsWith("OTT-")))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /** Provisioning is idempotent on orderId: a retried call returns 200 with the same externalRef, not a duplicate. */
    @Test
    void repeat_provision_for_same_order_is_idempotent() throws Exception {
        String orderId = newOrderId();
        String first = provision(validToken(), orderId, "sub-it", "OTT_NETFLIX_6M")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        provision(validToken(), orderId, "sub-it", "OTT_NETFLIX_6M")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalRef").value((String) JsonPath.read(first, "$.externalRef")));
    }

    /** No bearer token at all → 401 on the provisioning API (it never redirects to a login page). */
    @Test
    void no_token_is_401() throws Exception {
        provision(null, newOrderId(), "sub-it", "OTT_NETFLIX_6M").andExpect(status().isUnauthorized());
    }

    /** AudienceValidator: a token without the ott-service audience is rejected with 401, even though it is validly signed. */
    @Test
    void token_without_audience_is_401() throws Exception {
        String noAud = token(null, "ott:provision", ISSUER, Instant.now().plusSeconds(300), SIGNING_KEY);
        provision(noAud, newOrderId(), "sub-it", "OTT_NETFLIX_6M").andExpect(status().isUnauthorized());
    }

    /** A token minted for another resource server (aud=order-service) cannot be replayed here: 401. */
    @Test
    void token_for_another_audience_is_401() throws Exception {
        String otherAud = token("order-service", "ott:provision", ISSUER, Instant.now().plusSeconds(300), SIGNING_KEY);
        provision(otherAud, newOrderId(), "sub-it", "OTT_NETFLIX_6M").andExpect(status().isUnauthorized());
    }

    /** A valid token lacking the ott:provision scope is authenticated but forbidden: 403. */
    @Test
    void token_without_provision_scope_is_403() throws Exception {
        String noScope = token("ott-service", "openid profile", ISSUER, Instant.now().plusSeconds(300), SIGNING_KEY);
        provision(noScope, newOrderId(), "sub-it", "OTT_NETFLIX_6M").andExpect(status().isForbidden());
    }

    /** A token from a different issuer (iss mismatch) is rejected: 401. */
    @Test
    void token_from_wrong_issuer_is_401() throws Exception {
        String foreignIss = token("ott-service", "ott:provision", "http://evil.test/realms/vab", Instant.now().plusSeconds(300), SIGNING_KEY);
        provision(foreignIss, newOrderId(), "sub-it", "OTT_NETFLIX_6M").andExpect(status().isUnauthorized());
    }

    /** An expired token (beyond the 60s clock skew) is rejected: 401. */
    @Test
    void expired_token_is_401() throws Exception {
        String expired = token("ott-service", "ott:provision", ISSUER, Instant.now().minusSeconds(600), SIGNING_KEY);
        provision(expired, newOrderId(), "sub-it", "OTT_NETFLIX_6M").andExpect(status().isUnauthorized());
    }

    /** A token signed by a key not in the realm's JWKS (forged, same kid) fails signature verification: 401. */
    @Test
    void token_with_bad_signature_is_401() throws Exception {
        String forged = token("ott-service", "ott:provision", ISSUER, Instant.now().plusSeconds(300), FOREIGN_KEY);
        provision(forged, newOrderId(), "sub-it", "OTT_NETFLIX_6M").andExpect(status().isUnauthorized());
    }

    /** Demo triggers: OTTDOWN → 503 and OTTBAD → 422, with status-only bodies (no stack trace or exception name). */
    @Test
    void demo_failure_triggers_return_clean_errors() throws Exception {
        provision(validToken(), newOrderId(), "sub-it", "OTTDOWN_1M")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("Exception"))));
        provision(validToken(), newOrderId(), "sub-it", "OTTBAD_1M")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(not(containsString("Exception"))));
    }

    /** DELETE revokes the entitlement (204, status REVOKED); revoking an unknown ref is an idempotent 204. */
    @Test
    void revoke_marks_entitlement_revoked_and_is_idempotent() throws Exception {
        String json = provision(validToken(), newOrderId(), "sub-it", "OTT_PRIME_12M")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(json, "$.externalRef");

        mvc.perform(delete(API + "/" + ref).header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isNoContent());
        assertThat(entitlements.findById(ref).orElseThrow().getStatus()).isEqualTo(Entitlement.Status.REVOKED);

        mvc.perform(delete(API + "/OTT-UNKNOWN").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isNoContent());
    }

    // ── video surface: OIDC login (chain 2) ─────────────────────────────────

    /** Anonymous video requests are sent to Keycloak login (302 to the OAuth2 authorization endpoint), not served. */
    @Test
    void anonymous_video_request_redirects_to_login() throws Exception {
        mvc.perform(get("/v1/videos"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/oauth2/authorization/keycloak"));
    }

    /** A machine provisioning token is not a subscriber login: the video surface still redirects to login. */
    @Test
    void provisioning_bearer_cannot_use_video_surface() throws Exception {
        mvc.perform(get("/v1/videos").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().is3xxRedirection());
    }

    /** The seeded subscriber sub-alice holds Hotstar: streaming a Hotstar title is allowed (200, "Playing video"). */
    @Test
    void entitled_subscriber_can_stream() throws Exception {
        mvc.perform(get("/v1/videos/vid_hotstar_ipl/stream").with(oidcLogin().idToken(t -> t.claim("subscriberId", "sub-alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Playing video")));
    }

    /** sub-alice has no Netflix entitlement: streaming a Netflix title is 403; an unknown video is 404. */
    @Test
    void stream_without_entitlement_is_403_and_unknown_video_404() throws Exception {
        mvc.perform(get("/v1/videos/vid_netflix_film/stream").with(oidcLogin().idToken(t -> t.claim("subscriberId", "sub-alice"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/videos/vid_nope/stream").with(oidcLogin().idToken(t -> t.claim("subscriberId", "sub-alice"))))
                .andExpect(status().isNotFound());
    }

    /** End to end inside OTT: provisioning (M2M) unlocks streaming for that subscriber, and revoking locks it again (403). */
    @Test
    void provision_unlocks_and_revoke_relocks_streaming() throws Exception {
        String subscriber = "sub-it-" + UUID.randomUUID().toString().substring(0, 6);
        var login = oidcLogin().idToken(t -> t.claim("subscriberId", subscriber));
        mvc.perform(get("/v1/videos/vid_prime_doc/stream").with(login)).andExpect(status().isForbidden());

        String json = provision(validToken(), newOrderId(), subscriber, "OTT_PRIME_12M")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/v1/videos/vid_prime_doc/stream").with(login)).andExpect(status().isOk());

        mvc.perform(delete(API + "/" + JsonPath.read(json, "$.externalRef")).header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/videos/vid_prime_doc/stream").with(login)).andExpect(status().isForbidden());
    }

    /** Actuator health stays open for probes. */
    @Test
    void actuator_health_is_public() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ── stub Keycloak (JWKS only) ───────────────────────────────────────────

    private static RSAKey rsaKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Serves the realm JWKS (public half of SIGNING_KEY only) at Keycloak's certs path. */
    private static HttpServer startJwksStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/realms/vab/protocol/openid-connect/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
