package com.vab.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** §C2 B-2: the edge mints/keeps X-Correlation-Id, forwards it downstream and echoes it on the response. */
class CorrelationGlobalFilterTest {

    private final CorrelationGlobalFilter filter = new CorrelationGlobalFilter();

    /** Runs the filter and returns the exchange the chain (i.e. the routed request) received. */
    private static ServerWebExchange run(CorrelationGlobalFilter filter, MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> { forwarded.set(ex); return Mono.empty(); };
        filter.filter(exchange, chain).block();
        return forwarded.get();
    }

    /** No incoming id: a UUID is generated, put on the forwarded request and echoed on the response. */
    @Test
    void generates_id_when_absent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/offers"));

        ServerWebExchange forwarded = run(filter, exchange);

        String sent = forwarded.getRequest().getHeaders().getFirst("X-Correlation-Id");
        assertThatCode(() -> UUID.fromString(sent)).doesNotThrowAnyException();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id")).isEqualTo(sent);
    }

    /** An incoming id is kept unchanged both downstream and on the response. */
    @Test
    void keeps_caller_id() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/offers").header("X-Correlation-Id", "corr-42"));

        ServerWebExchange forwarded = run(filter, exchange);

        assertThat(forwarded.getRequest().getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-42");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-42");
    }

    /** A blank incoming id is treated as absent and replaced with a fresh UUID. */
    @Test
    void replaces_blank_id() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/offers").header("X-Correlation-Id", "  "));

        String sent = run(filter, exchange).getRequest().getHeaders().getFirst("X-Correlation-Id");

        assertThatCode(() -> UUID.fromString(sent)).doesNotThrowAnyException();
    }

    /** Runs first, so every later filter (and the downstream log line) sees the id. */
    @Test
    void has_highest_precedence() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
