package com.vab.order.query.api;

import com.vab.order.query.document.EntitlementView;
import com.vab.order.query.repository.EntitlementViewRepository;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "My Benefits" read API: maps the entitlements_v1 projection to a DTO list for
 * the subscriber's ACTIVE entitlements. Subject comes from the JWT {@code subscriberId}
 * claim (§A-3/A5), set here directly on the SecurityContext (no Keycloak).
 */
@ExtendWith(MockitoExtension.class)
class EntitlementQueryControllerTest {

    @Mock EntitlementViewRepository repo;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new EntitlementQueryController(repo))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private static void loginAs(String subscriberId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("subscriberId", subscriberId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returns_active_benefits_mapped_to_dto() throws Exception {
        loginAs("sub-9");
        EntitlementView e = new EntitlementView();
        e.setOfferCode("OTT_X_1M");
        e.setProductType("DIGITAL_SUBSCRIPTION");
        e.setStatus("ACTIVE");
        e.setExternalRef("OTT-REF9");
        e.setActivatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(repo.findBySubscriberIdAndStatus("sub-9", "ACTIVE")).thenReturn(List.of(e));

        mvc().perform(get("/v1/entitlements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].offerCode").value("OTT_X_1M"))
                .andExpect(jsonPath("$[0].productType").value("DIGITAL_SUBSCRIPTION"))
                .andExpect(jsonPath("$[0].externalRef").value("OTT-REF9"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void empty_when_no_active_benefits() throws Exception {
        loginAs("sub-0");
        when(repo.findBySubscriberIdAndStatus("sub-0", "ACTIVE")).thenReturn(List.of());

        mvc().perform(get("/v1/entitlements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
