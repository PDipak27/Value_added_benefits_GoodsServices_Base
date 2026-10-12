package com.vab.order.config;

import com.vab.order.command.api.OrderCommandController;
import com.vab.order.command.domain.OrderRepository;
import com.vab.order.command.service.OrderCommandService;
import com.vab.order.query.api.OrderSearchController;
import com.vab.order.query.api.OrderSearchService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resource-server role gate in order-service (§A-3/A5, defence in depth behind the gateway): the real
 * {@link SecurityConfig} filter chain in a {@code @WebMvcTest} slice. 401 without a token, 403 for a
 * subscriber on back-office POSTs and {@code /v1/ops/**}, pass-through for {@code vab-admin}.
 * Named *IT (not *Test) because it boots a Spring context (DD-25); no Docker needed.
 */
@WebMvcTest(controllers = {OrderCommandController.class, OrderSearchController.class})
@Import(SecurityConfig.class)
class OrderApiSecurityIT {

    private static final String IDEM = "3f1c2b7e-9a4d-4c1e-8b6a-1d2e3f4a5b6c";
    private static final String PLACE_BODY = """
            {"offerCode":"OTT_NETFLIX_6M","productType":"DIGITAL_SUBSCRIPTION","priceSnapshotId":"px-1",
             "amount":499,"currency":"INR","billingMode":"PAY_NOW"}""";

    @Autowired MockMvc mvc;

    @MockitoBean OrderCommandService commandService;
    @MockitoBean OrderRepository orderRepo;
    @MockitoBean OrderSearchService searchService;
    @MockitoBean JwtDecoder jwtDecoder;   // no Keycloak discovery in the slice

    private static JwtRequestPostProcessor subscriber() {
        return jwt().jwt(j -> j.claim("subscriberId", "sub-premium"));
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("ops-admin").claim("realm_access", Map.of("roles", List.of("vab-admin"))))
                .authorities(new SimpleGrantedAuthority("ROLE_vab-admin"));
    }

    /** Every order endpoint — subscriber and back-office — is 401 without a token, before any controller code runs. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/orders", "/v1/orders/ord-1/cancel", "/v1/orders/ord-1/retry-fulfilment",
            "/v1/orders/ord-1/complete-fulfilment", "/v1/orders/ord-1/revoke-entitlement"})
    void anonymous_post_is_401(String path) throws Exception {
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(commandService);
    }

    /** A subscriber token passes the chain for placement (CSRF is off for this stateless API): 202. */
    @Test
    void subscriber_can_place_order() throws Exception {
        when(commandService.placeOrder(any())).thenReturn("ord-1");

        mvc.perform(post("/v1/orders").with(subscriber()).header("Idempotency-Key", IDEM)
                        .contentType(MediaType.APPLICATION_JSON).content(PLACE_BODY))
                .andExpect(status().isAccepted());
    }

    /** A subscriber without vab-admin is 403 on every back-office POST, and the service is never reached. */
    @ParameterizedTest
    @ValueSource(strings = {"retry-fulfilment", "complete-fulfilment", "revoke-entitlement"})
    void subscriber_admin_action_is_403(String action) throws Exception {
        mvc.perform(post("/v1/orders/ord-1/" + action).with(subscriber())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalRef\":\"OTT-1\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(commandService);
    }

    /** vab-admin passes the role gate on the back-office POSTs: 202. */
    @ParameterizedTest
    @ValueSource(strings = {"retry-fulfilment", "complete-fulfilment", "revoke-entitlement"})
    void admin_action_is_202(String action) throws Exception {
        mvc.perform(post("/v1/orders/ord-1/" + action).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalRef\":\"OTT-1\"}"))
                .andExpect(status().isAccepted());
    }

    /** The ops search (/v1/ops/**) is admin-only: 401 anonymous, 403 subscriber, 200 admin. */
    @Test
    void ops_search_is_admin_only() throws Exception {
        mvc.perform(get("/v1/ops/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/ops/orders").with(subscriber())).andExpect(status().isForbidden());
        mvc.perform(get("/v1/ops/orders").with(admin())).andExpect(status().isOk());
    }

    /** End-to-end through the decoder: a Keycloak token's realm_access vab-admin role is mapped by KeycloakRealmRoleConverter and grants the back-office POST. */
    @Test
    void keycloak_realm_role_from_real_bearer_grants_admin() throws Exception {
        when(jwtDecoder.decode("admin-token")).thenReturn(Jwt.withTokenValue("admin-token").header("alg", "RS256")
                .subject("ops-admin").claim("realm_access", Map.of("roles", List.of("vab-admin"))).build());

        mvc.perform(post("/v1/orders/ord-1/retry-fulfilment").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isAccepted());
        verify(commandService).retryFulfilment("ord-1");
    }

    /** A bearer the decoder rejects (bad signature/expired) is 401 even for an otherwise-valid request. */
    @Test
    void rejected_bearer_is_401() throws Exception {
        when(jwtDecoder.decode("expired")).thenThrow(new BadJwtException("Jwt expired"));

        mvc.perform(post("/v1/orders/ord-1/cancel").header("Authorization", "Bearer expired"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(commandService);
    }
}
