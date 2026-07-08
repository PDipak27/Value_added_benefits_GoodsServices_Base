package com.vab.order.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** §A-3/A5: Keycloak realm roles → ROLE_* authorities (drives hasRole('vab-admin')). */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwt(Object realmAccess) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("u");
        if (realmAccess != null) b.claim("realm_access", realmAccess);
        return b.build();
    }

    @Test
    void maps_realm_roles_to_role_authorities() {
        var authorities = converter.convert(jwt(Map.of("roles", List.of("vab-admin", "default-roles-vab"))));
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_vab-admin", "ROLE_default-roles-vab");
    }

    @Test
    void empty_when_no_realm_access_claim() {
        assertThat(converter.convert(jwt(null))).isEmpty();
    }
}
