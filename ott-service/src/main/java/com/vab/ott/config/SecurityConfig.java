package com.vab.ott.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * Two security filter chains:
 * <ul>
 *   <li><b>Chain 1 (§A-1 / DD-29)</b> — the machine-to-machine provisioning API
 *       ({@code /ott/v1/entitlements/**}) is a <em>resource server</em>: a valid
 *       Keycloak JWT with scope {@code ott:provision} (and the {@code ott-service}
 *       audience, via {@link #jwtDecoder}).</li>
 *   <li><b>Chain 2 (§A-2)</b> — the subscriber video surface ({@code /v1/videos/**})
 *       is an <em>OIDC login</em> client: Authorization Code + PKCE against Keycloak,
 *       session-based. Actuator stays open.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Chain 1 — M2M provisioning API (Bearer JWT). */
    @Bean
    @Order(1)
    SecurityFilterChain provisioningApi(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/ott/v1/entitlements/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("SCOPE_ott:provision"))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
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

    /**
     * JWT decoder validating signature + issuer + expiry (defaults) <em>and</em> the
     * required audience (§A-1 hardening) — rejecting tokens not minted for this
     * resource server even if signed by the same realm.
     *
     * <p>Split-horizon Keycloak (EKS): when {@code jwk-set-uri} is set, keys are fetched
     * from that (in-cluster) URL and the {@code issuer-uri} (public hostname) is only
     * compared against the {@code iss} claim — no OIDC discovery through the public load
     * balancer, and no boot-time dependency on Keycloak. Without it (laptop), the decoder
     * discovers the JWKS from the issuer as before.
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${ott.required-audience:ott-service}") String audience) {
        NimbusJwtDecoder decoder = StringUtils.hasText(jwkSetUri)
                ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
                : NimbusJwtDecoder.withIssuerLocation(issuer).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(audience)));
        return decoder;
    }
}
