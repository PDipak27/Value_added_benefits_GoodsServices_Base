package com.vab.ott.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The partner API key (Design/06 §8): a match authenticates as ROLE_PARTNER for the request; anything else is 401. */
class ApiKeyAuthFilterTest {

    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter("key-123");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse call(String key, AtomicReference<Authentication> seen) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/ott/v1/entitlements");
        if (key != null) req.addHeader(ApiKeyAuthFilter.HEADER, key);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> seen.set(SecurityContextHolder.getContext().getAuthentication());
        filter.doFilter(req, res, chain);
        return res;
    }

    @Test
    void the_right_key_authenticates_the_partner_for_this_request_only() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();

        MockHttpServletResponse res = call("key-123", seen);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(seen.get().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_PARTNER");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void a_missing_or_wrong_key_is_401_and_never_reaches_the_api() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();

        assertThat(call(null, seen).getStatus()).isEqualTo(401);
        assertThat(call("key-124", seen).getStatus()).isEqualTo(401);
        assertThat(call("Bearer key-123", seen).getStatus()).isEqualTo(401);
        assertThat(seen.get()).isNull();
    }
}
