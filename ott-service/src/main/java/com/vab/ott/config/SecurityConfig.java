package com.vab.ott.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Two security filter chains:
 * <ul>
 *   <li><b>Chain 1 (Design/06 §8)</b> — the partner entitlement API ({@code /ott/v1/entitlements/**}) is
 *       authenticated with the partner's API key ({@link ApiKeyAuthFilter}, header {@code X-Api-Key}), as real
 *       OTT platforms do. Stateless, no Keycloak.</li>
 *   <li><b>Chain 2 (§A-2)</b> — the subscriber video surface ({@code /v1/videos/**}) is an <em>OIDC login</em>
 *       client: Authorization Code + PKCE against Keycloak, session-based. Actuator stays open.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Chain 1 — partner entitlement API (API key). */
    @Bean
    @Order(1)
    SecurityFilterChain partnerApi(HttpSecurity http, OttProperties props) throws Exception {
        http
                .securityMatcher("/ott/v1/entitlements/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthFilter(props.getApiKey()), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("PARTNER"));
        return http.build();
    }

    /** Chain 2 — subscriber video surface (OIDC login, session). Catch-all. */
    @Bean
    @Order(2)
    SecurityFilterChain videoSurface(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults());
        return http.build();
    }
}
