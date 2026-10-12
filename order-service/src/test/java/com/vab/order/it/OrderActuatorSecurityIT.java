package com.vab.order.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator exposure on order-service (full context, real infra): health status and info are public so
 * probes keep working, but health component details (DB/Mongo/Kafka) and metrics are vab-admin only.
 */
class OrderActuatorSecurityIT extends AbstractOrderIntegrationTest {

    @Autowired MockMvc mvc;

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("ops-admin").claim("realm_access", Map.of("roles", List.of("vab-admin"))))
                .authorities(new SimpleGrantedAuthority("ROLE_vab-admin"));
    }

    /** Health answers with a status (200 UP or 503 DOWN), never 401/403. */
    private static ResultMatcher healthStatusCode() {
        return result -> assertThat(result.getResponse().getStatus()).isIn(200, 503);
    }

    /** Anonymous health is reachable (probes) but shows only the status — no component details. */
    @Test
    void anonymous_health_shows_status_without_details() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(healthStatusCode())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    /** A vab-admin sees the health component breakdown (db, mongo, ...). */
    @Test
    void admin_health_shows_component_details() throws Exception {
        mvc.perform(get("/actuator/health").with(admin()))
                .andExpect(healthStatusCode())
                .andExpect(jsonPath("$.components.db").exists());
    }

    /** A plain subscriber is authenticated but not authorized for details: status only. */
    @Test
    void subscriber_health_shows_no_details() throws Exception {
        mvc.perform(get("/actuator/health").with(jwt().jwt(j -> j.claim("subscriberId", "sub-1"))))
                .andExpect(healthStatusCode())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    /** Info stays public. */
    @Test
    void info_is_public() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    /** Metrics are back-office: 401 anonymous, 403 subscriber, 200 vab-admin. */
    @Test
    void metrics_require_admin() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").with(jwt().jwt(j -> j.claim("subscriberId", "sub-1"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(admin())).andExpect(status().isOk());
    }
}
