package com.vab.fulfilment.ott;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * Fetches and caches a Keycloak <em>client-credentials</em> access token (§A-1 / DD-29)
 * so fulfilment can call the secured {@code ott-service} as a Bearer-authenticated
 * machine client. The token is cached until ~30s before expiry; refresh is synchronized.
 * (A small explicit provider rather than Spring Security's client stack keeps this
 * non-web participant free of a servlet security filter chain.)
 */
@Component
public class KeycloakTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(KeycloakTokenProvider.class);

    private final RestClient tokenClient;
    private final String clientId;
    private final String clientSecret;
    private final String scope;

    private volatile String  cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public KeycloakTokenProvider(
            RestClient.Builder builder,
            @Value("${keycloak.token-uri:http://localhost:8088/realms/vab/protocol/openid-connect/token}") String tokenUri,
            @Value("${keycloak.client-id:vab-provisioning}") String clientId,
            @Value("${keycloak.client-secret:vab-provisioning-secret}") String clientSecret,
            @Value("${keycloak.scope:ott:provision}") String scope) {
        this.tokenClient  = builder.baseUrl(tokenUri).build();
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
        this.scope        = scope;
    }

    /** A valid access token; fetches a fresh one when the cache is empty or near expiry. */
    public synchronized String token() {
        if (cachedToken != null && Instant.now().isBefore(expiresAt.minusSeconds(30))) {
        	log.info("using cachedToken Keycloak for client {} token:{}", clientId, cachedToken);
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("scope", scope);

        TokenResponse r = tokenClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (r == null || r.accessToken() == null) {
            throw new IllegalStateException("Keycloak returned no access_token for client " + clientId);
        }
        cachedToken = r.accessToken();
        expiresAt   = Instant.now().plusSeconds(r.expiresIn());
        log.info("Obtained Keycloak token for client {} (expires in {}s)", clientId, r.expiresIn());
        return cachedToken;
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("expires_in") long expiresIn) {}
}
