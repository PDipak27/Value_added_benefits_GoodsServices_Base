package com.vab.order.query.api;

import com.vab.order.command.domain.OrderRepository;
import com.vab.order.query.document.OrderView;
import com.vab.order.query.repository.OrderViewRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §B2 timeline + §A-3 object-level authorization: a subscriber sees only their own
 * order (owner → 200, non-owner → 404 with no existence leak, admin → any).
 */
@ExtendWith(MockitoExtension.class)
class OrderQueryControllerTest {

    @Mock OrderViewRepository repo;
    @Mock OrderRepository orderRepo;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new OrderQueryController(repo, orderRepo))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private static void loginAs(String subscriberId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("subscriberId", subscriberId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static void loginAsAdmin() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of("vab-admin"))).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static OrderView view(String orderId, String owner) {
        OrderView v = new OrderView();
        v.setOrderId(orderId);
        v.setSubscriberId(owner);
        v.setStatus("PLACED");
        v.addTimelineEntry(Instant.parse("2026-01-01T00:00:00Z"), "PLACED");
        v.addTimelineEntry(Instant.parse("2026-01-01T00:01:00Z"), "COMPLETED");
        return v;
    }

    @Test
    void get_returns_order_for_its_owner() throws Exception {
        loginAs("sub-1");
        when(repo.findById("ord-1")).thenReturn(Optional.of(view("ord-1", "sub-1")));

        mvc().perform(get("/v1/orders/ord-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ord-1"));
    }

    @Test
    void get_is_404_for_a_non_owner() throws Exception {
        loginAs("sub-2");
        when(repo.findById("ord-1")).thenReturn(Optional.of(view("ord-1", "sub-1")));

        mvc().perform(get("/v1/orders/ord-1"))
                .andExpect(status().isNotFound());   // not 403 — existence not revealed
    }

    @Test
    void get_admin_can_read_any_order() throws Exception {
        loginAsAdmin();
        when(repo.findById("ord-1")).thenReturn(Optional.of(view("ord-1", "sub-1")));

        mvc().perform(get("/v1/orders/ord-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ord-1"));
    }

    @Test
    void timeline_returns_the_audit_entries_for_owner() throws Exception {
        loginAs("sub-1");
        when(repo.findById("ord-1")).thenReturn(Optional.of(view("ord-1", "sub-1")));

        mvc().perform(get("/v1/orders/ord-1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PLACED"))
                .andExpect(jsonPath("$[1].status").value("COMPLETED"));
    }

    @Test
    void timeline_404_when_order_not_projected() throws Exception {
        loginAs("sub-1");
        when(repo.findById("missing")).thenReturn(Optional.empty());

        mvc().perform(get("/v1/orders/missing/timeline"))
                .andExpect(status().isNotFound());
    }

    @Test
    void timeline_404_for_a_non_owner() throws Exception {
        loginAs("sub-2");
        when(repo.findById("ord-1")).thenReturn(Optional.of(view("ord-1", "sub-1")));

        mvc().perform(get("/v1/orders/ord-1/timeline"))
                .andExpect(status().isNotFound());
    }
}
