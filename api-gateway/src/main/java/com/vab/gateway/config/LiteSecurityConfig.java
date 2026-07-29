package com.vab.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * LITE edge security — auth disabled (no Keycloak / OIDC). Permits every exchange so
 * the happy-path e2e can call the gateway without a Bearer token.
 *
 * <p>Active only under the {@code lite} profile; the secured {@link SecurityConfig}
 * is active otherwise. Without an explicit permit-all chain, Spring Security's
 * WebFlux default would secure all routes with generated-password HTTP Basic and
 * block the e2e. See Design/lite-cutlist.md.
 */
@Configuration
@EnableWebFluxSecurity
@Profile("lite")
public class LiteSecurityConfig {

    @Bean
    SecurityWebFilterChain liteSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll());
        return http.build();
    }
}
