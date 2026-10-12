package com.vab.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Edge authentication (§A-3/A5). The gateway validates Keycloak JWTs (JWKS via
 * issuer-uri) and forwards the Bearer downstream (token relay — services re-validate).
 *
 * <ul>
 *   <li>Actuator: only health (probes) and info are public; anything else needs {@code vab-admin}.</li>
 *   <li>Catalog browse ({@code GET /v1/offers/**}) and eligibility evaluate
 *       ({@code POST /v1/offers/{code}:evaluate}) are public; catalog writes (create,
 *       update, withdraw) require the {@code vab-admin} realm role.</li>
 *   <li>Back-office order actions (retry / complete / revoke) require the
 *       {@code vab-admin} realm role.</li>
 *   <li>Everything else routed here (orders, entitlements) requires a valid token.</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        // Probes + info only; every other actuator endpoint is back-office. (The
                        // gateway route-admin endpoint is not exposed at all — see application.yml.)
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/actuator/**").hasRole("vab-admin")
                        // Catalog: browse + eligibility evaluate are public; every other write
                        // (create / update / :withdraw) is back-office and needs vab-admin.
                        .pathMatchers(HttpMethod.GET, "/v1/offers/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/v1/offers/*:evaluate").permitAll()
                        .pathMatchers("/v1/offers/**").hasRole("vab-admin")
                        .pathMatchers(HttpMethod.POST,
                                "/v1/orders/*/retry-fulfilment",
                                "/v1/orders/*/complete-fulfilment",
                                "/v1/orders/*/revoke-entitlement").hasRole("vab-admin")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(realmRoleConverter())));
        return http.build();
    }

    private ReactiveJwtAuthenticationConverterAdapter realmRoleConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }
}
