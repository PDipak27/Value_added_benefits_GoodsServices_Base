package com.vab.gateway.ui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.net.URI;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * Test UI (static files under {@code classpath:/static/ui}) served by the gateway, so its API calls are
 * same-origin. {@code /ui/config.json} tells the page where Keycloak is: the token issuer the gateway
 * already validates against, so the same files work locally and on EKS.
 */
@Configuration
public class TestUiRoutes {

    @Bean
    RouterFunction<ServerResponse> testUi(
            @Value("${KEYCLOAK_ISSUER:${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:8088/realms/vab}}")
            String issuer,
            @Value("${vab.ui.client-id:vab-web}") String clientId) {
        Map<String, String> config = Map.of("issuer", issuer, "clientId", clientId);
        return route()
                .GET("/ui", r -> ServerResponse.temporaryRedirect(URI.create("/ui/index.html")).build())
                .GET("/ui/", r -> ServerResponse.temporaryRedirect(URI.create("/ui/index.html")).build())
                .GET("/ui/config.json", r -> ServerResponse.ok().bodyValue(config))
                .build();
    }
}
