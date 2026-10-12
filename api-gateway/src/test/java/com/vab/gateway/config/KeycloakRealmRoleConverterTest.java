package com.vab.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** §A-3/A5 edge: Keycloak realm roles → ROLE_* authorities (drives the gateway's hasRole('vab-admin')). */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwt(Object realmAccess) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("u");
        if (realmAccess != null) b.claim("realm_access", realmAccess);
        return b.build();
    }

    /** Every realm role becomes a ROLE_-prefixed authority, verbatim (no case change). */
    @Test
    void maps_realm_roles_to_role_authorities() {
        var authorities = converter.convert(jwt(Map.of("roles", List.of("vab-admin", "default-roles-vab"))));
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_vab-admin", "ROLE_default-roles-vab");
    }

    /** A token without realm_access (e.g. client_credentials) yields no authorities rather than failing. */
    @Test
    void empty_when_no_realm_access_claim() {
        assertThat(converter.convert(jwt(null))).isEmpty();
    }

    /** A malformed realm_access (roles not a list) is ignored, not trusted and not a 500. */
    @Test
    void empty_when_roles_is_not_a_list() {
        assertThat(converter.convert(jwt(Map.of("roles", "vab-admin")))).isEmpty();
    }
}
