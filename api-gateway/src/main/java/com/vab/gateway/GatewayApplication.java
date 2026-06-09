package com.vab.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * VA-BAGS edge / BFF.
 *
 * <p>Spring Cloud Gateway (reactive). Responsibilities for this iteration:
 * TLS termination (in front of it), request routing, and a single public
 * surface for external clients. Cross-cutting concerns that belong here per
 * {@code Design/02-service-responsibilities.md} but are deferred:
 * <ul>
 *   <li>JWT validation / OIDC Provider endpoints — added with
 *       {@code spring-boot-starter-oauth2-authorization-server} (commented out
 *       in the pom) in a later iteration.</li>
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
