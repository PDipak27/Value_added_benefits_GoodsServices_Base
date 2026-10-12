package com.vab.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §C2 B-2 HTTP edge of a service: the correlation id is in MDC for the whole request, echoed back, and never leaks to the next request. */
class CorrelationFilterTest {

    private final CorrelationFilter filter = new CorrelationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** Runs the filter and returns the MDC correlation id seen INSIDE the chain (i.e. by the controller's logs). */
    private String runAndCaptureMdc(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get(Correlation.MDC_CORRELATION_ID));
        filter.doFilter(request, response, chain);
        return seen.get();
    }

    /** The id relayed by the gateway is used as-is in MDC and echoed on the response. */
    @Test
    void uses_incoming_header() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/orders");
        request.addHeader(Correlation.HEADER, "corr-gw-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(runAndCaptureMdc(request, response)).isEqualTo("corr-gw-1");
        assertThat(response.getHeader(Correlation.HEADER)).isEqualTo("corr-gw-1");
    }

    /** A direct call without the header (or with a blank one) gets a fresh UUID, used in MDC and echoed. */
    @Test
    void generates_id_when_absent_or_blank() throws Exception {
        for (String incoming : new String[] {null, "  "}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/orders");
            if (incoming != null) request.addHeader(Correlation.HEADER, incoming);
            MockHttpServletResponse response = new MockHttpServletResponse();

            String inChain = runAndCaptureMdc(request, response);

            assertThatCode(() -> UUID.fromString(inChain)).doesNotThrowAnyException();
            assertThat(response.getHeader(Correlation.HEADER)).isEqualTo(inChain);
        }
    }

    /** After the request (pooled thread reused) no correlation/causation/message ids remain in MDC. */
    @Test
    void clears_mdc_after_request() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/orders");
        request.addHeader(Correlation.HEADER, "corr-1");
        FilterChain chain = (req, res) -> {
            MDC.put(Correlation.MDC_CAUSATION_ID, "cause-1");   // e.g. set by a Tram send inside the request
            MDC.put(Correlation.MDC_MESSAGE_ID, "msg-1");
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(MDC.get(Correlation.MDC_CORRELATION_ID)).isNull();
        assertThat(MDC.get(Correlation.MDC_CAUSATION_ID)).isNull();
        assertThat(MDC.get(Correlation.MDC_MESSAGE_ID)).isNull();
    }

    /** Even when the handler throws, the MDC is cleared (finally), so the id can't leak into the next request. */
    @Test
    void clears_mdc_when_chain_throws() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/orders");
        FilterChain chain = (req, res) -> { throw new IllegalStateException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), chain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(Correlation.MDC_CORRELATION_ID)).isNull();
    }
}
