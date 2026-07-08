package com.vab.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource-server security (§A-3/A5, DD-31). order-service re-validates the Keycloak
 * JWT relayed by the gateway (defence in depth) and derives {@code subscriberId} from
 * the token.
 *
 * <ul>
 *   <li>Back-office actions — order re-drive / manual complete / entitlement revoke,
 *       and the {@code /v1/ops/**} search — require the {@code vab-admin} realm role.</li>
 *   <li>All other {@code /v1/**} endpoints require a valid token; the subscriber-facing
 *       ones read the subject from the {@code subscriberId} claim.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/v1/orders/*/retry-fulfilment",
                                "/v1/orders/*/complete-fulfilment",
                                "/v1/orders/*/revoke-entitlement").hasRole("vab-admin")
                        .requestMatchers("/v1/ops/**").hasRole("vab-admin")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    private JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }
}
