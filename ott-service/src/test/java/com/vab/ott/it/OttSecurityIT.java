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
import com.vab.ott.domain.EntitlementActivator;
import com.vab.ott.domain.EntitlementRepository;
import com.vab.ott.webhook.WebhookDelivery;
import com.vab.ott.webhook.WebhookEvent;
import com.vab.ott.webhook.WebhookEventRepository;
import com.vab.ott.webhook.WebhookOutbox;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * ott-service on real infra (Design/06 §8, §A-2). The partner API is authenticated with the API key, accepts
 * provisioning as PENDING, and the activator + webhook outbox finish it: the test drives both (their schedules
 * are slowed to an hour) and a local HTTP stub plays the partner's webhook endpoint. The video surface uses
 * {@code oidcLogin()} to test the entitlement gate on {@code /stream}.
 *
 * <p>Runs with the {@code eks} profile: its split-horizon config uses explicit Keycloak endpoints (pointed at the
 * stub) instead of boot-time OIDC discovery — no Keycloak needed.
 */
@SpringBootTest(properties = {
        "KEYCLOAK_ISSUER=" + OttSecurityIT.ISSUER,
        "ott.api-key=" + OttSecurityIT.API_KEY,
        "ott.webhook-secret=whsec_it",
        "ott.activation-delay=0s",
        "ott.activation-poll-interval=1h",
        "ott.webhook-poll-interval=1h"})
@AutoConfigureMockMvc
@ActiveProfiles("eks")
class OttSecurityIT {

    static final String ISSUER = "http://issuer.test/realms/vab";
    static final String API_KEY = "ott_it_key";
    private static final String API = "/ott/v1/entitlements";

    static final RSAKey SIGNING_KEY = rsaKey("k1");
    static final List<String[]> received = new CopyOnWriteArrayList<>();   // {signature, body} the partner got
    static final HttpServer stub = startStub();

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
                    .withDatabaseName("vab").withUsername("eventuate").withPassword("eventuate").withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://localhost:" + stub.getAddress().getPort();
        r.add("KEYCLOAK_PUBLIC_URL", () -> base);
        r.add("KEYCLOAK_INTERNAL_URL", () -> base);
        r.add("ott.webhook-url", () -> base + "/webhooks/ott");
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired EntitlementRepository entitlements;
    @Autowired WebhookEventRepository webhookEvents;
    @Autowired EntitlementActivator activator;
    @Autowired WebhookDelivery delivery;

    private ResultActions provision(String apiKey, String orderId, String subscriberId, String offerCode) throws Exception {
        var req = post(API).contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId":"%s","subscriberId":"%s","offerCode":"%s"}""".formatted(orderId, subscriberId, offerCode));
        if (apiKey != null) req.header("X-Api-Key", apiKey);
        return mvc.perform(req);
    }

    private String accepted(String orderId, String subscriberId, String offerCode) throws Exception {
        String json = provision(API_KEY, orderId, subscriberId, offerCode)
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.externalRef");
    }

    private static String newOrderId() {
        return "ord_it_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private List<WebhookEvent> eventsFor(String orderId) {
        return webhookEvents.findAll().stream().filter(e -> e.getPayload().contains(orderId)).toList();
    }

    // ── partner API: API key (chain 1) ──────────────────────────────────────

    /** A valid API key gets the request accepted: 202 PENDING with an externalRef, no webhook yet. */
    @Test
    void valid_api_key_accepts_provisioning_as_pending() throws Exception {
        String orderId = newOrderId();

        provision(API_KEY, orderId, "sub-it", "OTT_NETFLIX_6M")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.externalRef").value(org.hamcrest.Matchers.startsWith("OTT-")))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(eventsFor(orderId)).isEmpty();
    }

    /** The activator turns it ACTIVE and queues a signed entitlement.activated webhook in the same transaction. */
    @Test
    void activation_makes_it_active_and_delivers_a_signed_webhook() throws Exception {
        String orderId = newOrderId();
        String ref = accepted(orderId, "sub-it", "OTT_NETFLIX_6M");

        activator.activateDue();

        assertThat(entitlements.findById(ref).orElseThrow().getStatus()).isEqualTo(Entitlement.Status.ACTIVE);
        assertThat(eventsFor(orderId)).singleElement().satisfies(e -> {
            assertThat(e.getType()).isEqualTo(WebhookOutbox.ACTIVATED);
            assertThat(e.getStatus()).isEqualTo(WebhookEvent.Status.PENDING);
        });

        delivery.deliverDue();

        assertThat(eventsFor(orderId)).singleElement().satisfies(e -> assertThat(e.getStatus()).isEqualTo(WebhookEvent.Status.DELIVERED));
        String[] got = received.stream().filter(r -> r[1].contains(orderId)).findFirst().orElseThrow();
        long t = Long.parseLong(got[0].split(",")[0].substring(2));
        assertThat(got[0]).endsWith("v1=" + WebhookOutbox.sign("whsec_it", t, got[1]));
        assertThat((String) JsonPath.read(got[1], "$.data.externalRef")).isEqualTo(ref);
    }

    /** Demo trigger OTTREJECT: accepted, then rejected by the activator with an entitlement.rejected webhook. */
    @Test
    void activator_can_reject_and_says_so_by_webhook() throws Exception {
        String orderId = newOrderId();
        String ref = accepted(orderId, "sub-it", "OTT_OTTREJECT_1M");

        activator.activateDue();

        assertThat(entitlements.findById(ref).orElseThrow().getStatus()).isEqualTo(Entitlement.Status.REJECTED);
        assertThat(eventsFor(orderId)).singleElement().satisfies(e -> assertThat(e.getType()).isEqualTo(WebhookOutbox.REJECTED));
    }

    /** Idempotent on orderId: a retry returns 200 with the same ref and the current state (here ACTIVE + validity). */
    @Test
    void repeat_provision_returns_the_current_state() throws Exception {
        String orderId = newOrderId();
        String ref = accepted(orderId, "sub-it", "OTT_NETFLIX_6M");
        activator.activateDue();

        provision(API_KEY, orderId, "sub-it", "OTT_NETFLIX_6M")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalRef").value(ref))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /** Missing or wrong API key → 401 before any controller; a Keycloak bearer token is no substitute. */
    @Test
    void missing_wrong_key_or_a_bearer_token_is_401() throws Exception {
        provision(null, newOrderId(), "sub-it", "OTT_NETFLIX_6M").andExpect(status().isUnauthorized());
        provision("wrong-key", newOrderId(), "sub-it", "OTT_NETFLIX_6M").andExpect(status().isUnauthorized());
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + keycloakToken())
                        .content("{\"orderId\":\"" + newOrderId() + "\",\"subscriberId\":\"s\",\"offerCode\":\"OTT_NETFLIX_6M\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** Demo triggers: OTTDOWN → 503 and OTTBAD → 422, synchronous, with status-only bodies. */
    @Test
    void demo_failure_triggers_return_clean_errors() throws Exception {
        provision(API_KEY, newOrderId(), "sub-it", "OTTDOWN_1M")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("Exception"))));
        provision(API_KEY, newOrderId(), "sub-it", "OTTBAD_1M")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(not(containsString("Exception"))));
    }

    /** DELETE revokes (204, REVOKED) and is idempotent; a revoked PENDING entitlement is never activated. */
    @Test
    void revoke_is_idempotent_and_stops_a_pending_activation() throws Exception {
        String orderId = newOrderId();
        String ref = accepted(orderId, "sub-it", "OTT_PRIME_12M");

        mvc.perform(delete(API + "/" + ref).header("X-Api-Key", API_KEY)).andExpect(status().isNoContent());
        mvc.perform(delete(API + "/OTT-UNKNOWN").header("X-Api-Key", API_KEY)).andExpect(status().isNoContent());
        activator.activateDue();

        assertThat(entitlements.findById(ref).orElseThrow().getStatus()).isEqualTo(Entitlement.Status.REVOKED);
        assertThat(eventsFor(orderId)).isEmpty();
    }

    /** DELETE by-order revokes without the ref (204), idempotently, and needs the API key. */
    @Test
    void revoke_by_order_revokes_without_the_external_ref() throws Exception {
        String orderId = newOrderId();
        String ref = accepted(orderId, "sub-it", "OTT_PRIME_12M");
        activator.activateDue();

        mvc.perform(delete(API + "/by-order/" + orderId).header("X-Api-Key", API_KEY)).andExpect(status().isNoContent());
        assertThat(entitlements.findById(ref).orElseThrow().getStatus()).isEqualTo(Entitlement.Status.REVOKED);

        mvc.perform(delete(API + "/by-order/" + orderId).header("X-Api-Key", API_KEY)).andExpect(status().isNoContent());
        mvc.perform(delete(API + "/by-order/ord-unknown").header("X-Api-Key", API_KEY)).andExpect(status().isNoContent());
        mvc.perform(delete(API + "/by-order/" + orderId)).andExpect(status().isUnauthorized());
    }

    // ── video surface: OIDC login (chain 2) ─────────────────────────────────

    /** Anonymous video requests are sent to Keycloak login (302 to the OAuth2 authorization endpoint), not served. */
    @Test
    void anonymous_video_request_redirects_to_login() throws Exception {
        mvc.perform(get("/v1/videos"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/oauth2/authorization/keycloak"));
    }

    /** The partner API key is not a subscriber login: the video surface still redirects to login. */
    @Test
    void partner_api_key_cannot_use_video_surface() throws Exception {
        mvc.perform(get("/v1/videos").header("X-Api-Key", API_KEY)).andExpect(status().is3xxRedirection());
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

    /** Inside OTT: a PENDING entitlement doesn't unlock streaming, activation does, and a revoke locks it again. */
    @Test
    void activation_unlocks_and_revoke_relocks_streaming() throws Exception {
        String subscriber = "sub-it-" + UUID.randomUUID().toString().substring(0, 6);
        var login = oidcLogin().idToken(t -> t.claim("subscriberId", subscriber));

        String ref = accepted(newOrderId(), subscriber, "OTT_PRIME_12M");
        mvc.perform(get("/v1/videos/vid_prime_doc/stream").with(login)).andExpect(status().isForbidden());

        activator.activateDue();
        mvc.perform(get("/v1/videos/vid_prime_doc/stream").with(login)).andExpect(status().isOk());

        mvc.perform(delete(API + "/" + ref).header("X-Api-Key", API_KEY)).andExpect(status().isNoContent());
        mvc.perform(get("/v1/videos/vid_prime_doc/stream").with(login)).andExpect(status().isForbidden());
    }

    // ── stubs ───────────────────────────────────────────────────────────────

    /** A Keycloak-style client-credentials token: the old credential, no longer accepted by the partner API. */
    private static String keycloakToken() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("service-account-vab-provisioning")
                .audience("ott-service").claim("scope", "ott:provision")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(SIGNING_KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey rsaKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Plays Keycloak's JWKS endpoint (for the login client's config) and the partner's webhook endpoint. */
    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/realms/vab/protocol/openid-connect/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
                exchange.close();
            });
            server.createContext("/webhooks/ott", exchange -> {
                received.add(new String[]{exchange.getRequestHeaders().getFirst(WebhookDelivery.SIGNATURE_HEADER),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)});
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
