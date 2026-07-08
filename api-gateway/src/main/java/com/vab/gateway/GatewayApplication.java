package com.vab.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * VA-BAGS edge / BFF.
 *
 * <p>Spring Cloud Gateway (reactive). Responsibilities: request routing, and a
 * single public surface for external clients. Since §A-3/DD-31 it is also an
 * <b>edge OAuth2 resource server</b> — it validates Keycloak JWTs (JWKS), relays
 * the Bearer downstream, and gates back-office routes on the {@code vab-admin}
 * role (see {@code config/SecurityConfig}). The OIDC Provider is Keycloak, not the
 * gateway (DD-29). Still deferred:
 * <ul>
 *   <li>Rate limiting — Redis-backed {@code RequestRateLimiter} filter, later.</li>
 * </ul>
 *
 * <p>Routing is declarative in {@code application.yml}; only published REST
 * surfaces (catalog + order command/query) are exposed. Inventory, billing and
 * notification are internal (saga participants / event consumers) and are
 * deliberately not routed.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
