package com.vab.ott.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §A-1 hardening: a token is accepted only when it carries the {@code ott-service}
 * audience, rejecting tokens minted for a different resource server.
 */
class AudienceValidatorTest {

    private final AudienceValidator validator = new AudienceValidator("ott-service");

    private static Jwt jwt(List<String> audience) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("svc");
        if (audience != null) b.audience(audience);
        return b.build();
    }

    @Test
    void passes_when_required_audience_present() {
        assertThat(validator.validate(jwt(List.of("ott-service", "account"))).hasErrors()).isFalse();
    }

    @Test
    void fails_when_audience_does_not_match() {
        assertThat(validator.validate(jwt(List.of("account"))).hasErrors()).isTrue();
    }

    @Test
    void fails_when_no_audience_claim() {
        assertThat(validator.validate(jwt(null)).hasErrors()).isTrue();
    }
}
