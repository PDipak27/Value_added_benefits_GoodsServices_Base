package com.vab.paymentsim;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** The merchant API needs the API key; the checkout page doesn't (its client secret guards it). */
class ApiKeyFilterTest {

    private final PspProperties props = new PspProperties();
    private final ApiKeyFilter filter = new ApiKeyFilter(props);

    private MockHttpServletResponse call(String uri, String authorization) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        if (authorization != null) req.addHeader("Authorization", authorization);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        if (chain.getRequest() != null) res.setStatus(200);   // reached the controller
        return res;
    }

    private static String basic(String user, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void correct_key_passes() throws Exception {
        assertThat(call("/v1/payment_intents", basic(props.getKeyId(), props.getKeySecret())).getStatus()).isEqualTo(200);
    }

    @Test
    void missing_or_wrong_key_is_401() throws Exception {
        assertThat(call("/v1/payment_intents", null).getStatus()).isEqualTo(401);
        assertThat(call("/v1/payment_intents", basic(props.getKeyId(), "nope")).getStatus()).isEqualTo(401);
        assertThat(call("/v1/payment_intents/pi_1/capture", "Bearer something").getStatus()).isEqualTo(401);
    }

    @Test
    void checkout_pages_are_not_behind_the_key() throws Exception {
        assertThat(call("/checkout/pi_1/decision", null).getStatus()).isEqualTo(200);
    }
}
