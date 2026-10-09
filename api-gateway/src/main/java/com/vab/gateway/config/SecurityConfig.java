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
 *   <li>Catalog browse ({@code /v1/offers/**}) is public.</li>
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
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/v1/offers/**").permitAll()
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
