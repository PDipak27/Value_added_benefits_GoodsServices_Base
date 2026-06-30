package com.vab.fulfilment.ott;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Client-credentials token fetch (§A-1): the access token is parsed and cached —
 * a second call within the validity window does NOT hit Keycloak again.
 */
class KeycloakTokenProviderTest {

    private static final String TOKEN_URI = "http://kc/realms/vab/protocol/openid-connect/token";

    @Test
    void fetches_a_token_then_serves_it_from_cache() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Exactly ONE token request even though token() is called twice.
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"abc.def.ghi\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));

        KeycloakTokenProvider provider = new KeycloakTokenProvider(
                builder, TOKEN_URI, "vab-provisioning", "secret", "ott:provision");

        assertThat(provider.token()).isEqualTo("abc.def.ghi");
        assertThat(provider.token()).isEqualTo("abc.def.ghi");   // from cache, no second call
        server.verify();
    }
}
