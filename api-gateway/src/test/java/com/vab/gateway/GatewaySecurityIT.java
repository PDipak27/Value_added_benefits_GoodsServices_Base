package com.vab.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Edge security (§A-3/A5) — route × role matrix of {@code SecurityConfig} through the real gateway:
 * catalog browse/evaluate are public, orders/entitlements need a token (401), back-office POSTs and
 * catalog writes (create/update/withdraw) need {@code vab-admin} (403).
 *
 * <p>The three routes are re-pointed (same ids/predicates as application.yml, the way k8s overrides them)
 * at an in-process JDK HTTP stub that echoes what it received in {@code X-Seen-*} response headers, so a
 * 200 proves the request passed security AND was routed. No Docker; Keycloak is replaced by a mocked
 * {@link ReactiveJwtDecoder} and TLS is off.
 */
@SpringBootTest(properties = "server.ssl.enabled=false")
class GatewaySecurityIT {

    static final HttpServer downstream = startDownstreamStub();

    @DynamicPropertySource
    static void routesToStub(DynamicPropertyRegistry r) {
        String uri = "http://localhost:" + downstream.getAddress().getPort();
        String[][] routes = {
                {"catalog-service",    "Path=/v1/offers/**"},
                {"order-service",      "Path=/v1/orders/**"},
                {"order-entitlements", "Path=/v1/entitlements/**"}};
        for (int i = 0; i < routes.length; i++) {
            String id = routes[i][0], predicate = routes[i][1];
            r.add("spring.cloud.gateway.routes[" + i + "].id", () -> id);
            r.add("spring.cloud.gateway.routes[" + i + "].uri", () -> uri);
            r.add("spring.cloud.gateway.routes[" + i + "].predicates[0]", () -> predicate);
        }
    }

    @AfterAll
    static void stopStub() {
        downstream.stop(0);
    }

    @MockitoBean ReactiveJwtDecoder jwtDecoder;
    @Autowired ApplicationContext context;

    WebTestClient client;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                // A context-bound client sends no Host header; the PreserveHostHeader default filter then hands
                // NettyRoutingFilter a null Host → NPE/500 on every routed call. Real HTTP/1.1 clients always send it.
                .defaultHeader(HttpHeaders.HOST, "localhost")
                // First routed call warms up the Netty client pool; 5s default is too tight on a cold/slow host.
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ── Public surface ──────────────────────────────────────────────────────

    /** Catalog browse is public: an anonymous GET passes security and reaches catalog-service. */
    @Test
    void anonymous_catalog_browse_is_routed() {
        client.get().uri("/v1/offers/OTT_NETFLIX_6M").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Path", "/v1/offers/OTT_NETFLIX_6M");
    }

    /** Eligibility evaluate is a POST under /v1/offers/** and is public too (no admin/token gate). */
    @Test
    void anonymous_catalog_evaluate_post_is_routed() {
        client.post().uri("/v1/offers/OTT_NETFLIX_6M:evaluate")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Method", "POST");
    }

    /** Actuator health is reachable without a token (k8s probes hit it anonymously). */
    @Test
    void actuator_health_is_public() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    // ── Authenticated surface ───────────────────────────────────────────────

    /** Order and entitlement reads without a token are rejected at the edge with 401 (never routed). */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/orders", "/v1/orders/ord-1", "/v1/entitlements"})
    void anonymous_order_and_entitlement_reads_get_401(String path) {
        client.get().uri(path).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist("X-Seen-Path");
    }

    /** Placing an order anonymously is 401: POST /v1/orders is not public. */
    @Test
    void anonymous_place_order_gets_401() {
        client.post().uri("/v1/orders").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isUnauthorized();
    }

    /** Any valid subscriber token reaches the order and entitlement routes. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/orders", "/v1/orders/ord-1/timeline", "/v1/entitlements"})
    void subscriber_reads_are_routed(String path) {
        client.mutateWith(subscriber()).get().uri(path).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Path", path);
    }

    /** Subscriber-facing writes (place, cancel) need a token only — no admin role. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/orders", "/v1/orders/ord-1/cancel"})
    void subscriber_writes_are_routed(String path) {
        client.mutateWith(subscriber()).post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Path", path);
    }

    // ── Catalog admin writes (vab-admin) ────────────────────────────────────

    /** Catalog writes (create, withdraw) without a token are 401 at the edge and never reach catalog-service. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/offers", "/v1/offers/OTT_NETFLIX_6M:withdraw"})
    void anonymous_catalog_write_gets_401(String path) {
        client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist("X-Seen-Path");
    }

    /** Anonymous offer update (PUT) is 401 as well — only GET and :evaluate are public under /v1/offers. */
    @Test
    void anonymous_catalog_update_gets_401() {
        client.put().uri("/v1/offers/OTT_NETFLIX_6M").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** A subscriber without vab-admin cannot create, update or withdraw offers: 403, not routed. */
    @Test
    void subscriber_catalog_writes_get_403() {
        client.mutateWith(subscriber()).post().uri("/v1/offers")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isForbidden().expectHeader().doesNotExist("X-Seen-Path");
        client.mutateWith(subscriber()).put().uri("/v1/offers/OTT_NETFLIX_6M")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(subscriber()).post().uri("/v1/offers/OTT_NETFLIX_6M:withdraw")
                .exchange().expectStatus().isForbidden();
    }

    /** vab-admin passes the catalog write gate: create, update and withdraw are routed to catalog-service. */
    @Test
    void admin_catalog_writes_are_routed() {
        client.mutateWith(admin()).post().uri("/v1/offers")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Method", "POST");
        client.mutateWith(admin()).put().uri("/v1/offers/OTT_NETFLIX_6M")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Method", "PUT");
        client.mutateWith(admin()).post().uri("/v1/offers/OTT_NETFLIX_6M:withdraw")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Path", "/v1/offers/OTT_NETFLIX_6M:withdraw");
    }

    /** The :evaluate exception is exact: a POST to another :action suffix on an offer is still admin-gated (401 anonymous). */
    @Test
    void only_evaluate_is_a_public_post() {
        client.post().uri("/v1/offers/OTT_NETFLIX_6M:publish").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isUnauthorized();
    }

    // ── Back-office (vab-admin) surface ─────────────────────────────────────

    /** Back-office POSTs without a token are 401 (authentication comes before the role check). */
    @ParameterizedTest
    @ValueSource(strings = {"retry-fulfilment", "complete-fulfilment", "revoke-entitlement"})
    void anonymous_admin_action_gets_401(String action) {
        client.post().uri("/v1/orders/ord-1/" + action).exchange()
                .expectStatus().isUnauthorized();
    }

    /** A subscriber without {@code vab-admin} is authenticated but forbidden: 403, not routed. */
    @ParameterizedTest
    @ValueSource(strings = {"retry-fulfilment", "complete-fulfilment", "revoke-entitlement"})
    void subscriber_admin_action_gets_403(String action) {
        client.mutateWith(subscriber()).post().uri("/v1/orders/ord-1/" + action).exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("X-Seen-Path");
    }

    /** With {@code ROLE_vab-admin} the back-office POSTs pass the edge and are routed to order-service. */
    @ParameterizedTest
    @ValueSource(strings = {"retry-fulfilment", "complete-fulfilment", "revoke-entitlement"})
    void admin_action_is_routed(String action) {
        client.mutateWith(admin()).post().uri("/v1/orders/ord-1/" + action)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Path", "/v1/orders/ord-1/" + action);
    }

    // ── Real bearer token: JWT decode → realm-role converter → token relay ──

    /** A Keycloak token whose realm_access.roles has vab-admin is mapped to ROLE_vab-admin and relayed downstream unchanged. */
    @Test
    void keycloak_realm_role_grants_admin_and_bearer_is_relayed() {
        when(jwtDecoder.decode("admin-token")).thenReturn(Mono.just(keycloakJwt("admin-token", List.of("vab-admin"))));

        client.post().uri("/v1/orders/ord-1/retry-fulfilment")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Authorization", "Bearer admin-token");
    }

    /** A Keycloak token with only default realm roles is 403 on a back-office POST. */
    @Test
    void keycloak_token_without_admin_role_gets_403() {
        when(jwtDecoder.decode("user-token")).thenReturn(Mono.just(keycloakJwt("user-token", List.of("default-roles-vab"))));

        client.post().uri("/v1/orders/ord-1/revoke-entitlement")
                .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                .exchange()
                .expectStatus().isForbidden();
    }

    /** An invalid/expired bearer (decoder rejects it) is 401 with a Bearer challenge, not routed. */
    @Test
    void invalid_bearer_gets_401_with_challenge() {
        when(jwtDecoder.decode("expired")).thenReturn(Mono.error(new BadJwtException("Jwt expired")));

        client.get().uri("/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer expired")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, v -> assertThat(v).startsWith("Bearer"));
    }

    // ── Routing scope + correlation id ──────────────────────────────────────

    /** Internal services (inventory/billing/notification) are not routed: even an admin gets 404 at the edge. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/inventory/items", "/v1/billing/accounts/sub-premium", "/v1/notifications"})
    void internal_services_are_not_exposed(String path) {
        client.mutateWith(admin()).get().uri(path).exchange().expectStatus().isNotFound();
    }

    /** With no X-Correlation-Id the gateway mints one, forwards it downstream and echoes the same value. */
    @Test
    void correlation_id_is_generated_forwarded_and_echoed() {
        var result = client.get().uri("/v1/offers").exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();

        String echoed = result.getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(echoed).isNotBlank();
        assertThat(result.getResponseHeaders().getFirst("X-Seen-Correlation-Id")).isEqualTo(echoed);
    }

    /** A caller-supplied X-Correlation-Id is kept as-is end to end (not replaced). */
    @Test
    void caller_correlation_id_is_preserved() {
        client.get().uri("/v1/offers").header("X-Correlation-Id", "corr-123").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "corr-123")
                .expectHeader().valueEquals("X-Seen-Correlation-Id", "corr-123");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static JwtMutator subscriber() {
        return mockJwt().jwt(j -> j.claim("subscriberId", "sub-premium"));
    }

    private static JwtMutator admin() {
        return mockJwt().jwt(j -> j.subject("ops-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_vab-admin"));
    }

    private static Jwt keycloakJwt(String token, List<String> realmRoles) {
        return Jwt.withTokenValue(token).header("alg", "RS256").subject("kc-user")
                .claim("subscriberId", "sub-premium")
                .claim("realm_access", Map.of("roles", realmRoles))
                .build();
    }

    /** Downstream stand-in: 200 + {@code X-Seen-*} headers describing the forwarded request. */
    private static HttpServer startDownstreamStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                var in = exchange.getRequestHeaders();
                var out = exchange.getResponseHeaders();
                out.add("X-Seen-Method", exchange.getRequestMethod());
                out.add("X-Seen-Path", exchange.getRequestURI().getRawPath());
                if (in.containsKey("Authorization"))    out.add("X-Seen-Authorization", in.getFirst("Authorization"));
                if (in.containsKey("X-Correlation-Id")) out.add("X-Seen-Correlation-Id", in.getFirst("X-Correlation-Id"));
                out.add("Content-Type", "application/json");
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
