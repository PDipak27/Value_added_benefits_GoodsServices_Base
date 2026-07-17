package com.vab.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * §C2 B-2: at the HTTP edge, seed a correlation id into the MDC (from the inbound
 * {@code X-Correlation-Id} header — set by the gateway — or freshly generated) so
 * every log line during the request carries it, and echo it back on the response.
 * Cleared in {@code finally} so ids never leak across pooled threads.
 */
public class CorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(Correlation.HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(Correlation.MDC_CORRELATION_ID, correlationId);
        response.setHeader(Correlation.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Correlation.MDC_CORRELATION_ID);
            MDC.remove(Correlation.MDC_CAUSATION_ID);
            MDC.remove(Correlation.MDC_MESSAGE_ID);
        }
    }
}
