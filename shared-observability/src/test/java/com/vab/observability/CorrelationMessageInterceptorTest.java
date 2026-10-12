package com.vab.observability;

import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.producer.MessageBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * §C2 B-2 across the async saga boundary: preSend stamps correlationId (+ causationId = the message being
 * handled) onto outgoing Tram messages; preHandle/postHandle restore and then clear the MDC on the consumer.
 */
class CorrelationMessageInterceptorTest {

    private final CorrelationMessageInterceptor interceptor = new CorrelationMessageInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static Message message() {
        return MessageBuilder.withPayload("{}").withHeader(Message.ID, "msg-" + UUID.randomUUID()).build();
    }

    /** A send triggered by an HTTP request stamps that request's correlationId and no causationId (it is the first hop). */
    @Test
    void presend_from_http_request_stamps_correlation_only() {
        MDC.put(Correlation.MDC_CORRELATION_ID, "corr-http");
        Message out = message();

        interceptor.preSend(out);

        assertThat(out.getHeader(Correlation.MDC_CORRELATION_ID)).contains("corr-http");
        assertThat(out.getHeader(Correlation.MDC_CAUSATION_ID)).isEmpty();
    }

    /** A send made while handling a message keeps the correlationId and records that message's id as causationId. */
    @Test
    void presend_while_handling_a_message_records_causation() {
        MDC.put(Correlation.MDC_CORRELATION_ID, "corr-saga");
        MDC.put(Correlation.MDC_MESSAGE_ID, "msg-incoming");
        Message out = message();

        interceptor.preSend(out);

        assertThat(out.getHeader(Correlation.MDC_CORRELATION_ID)).contains("corr-saga");
        assertThat(out.getHeader(Correlation.MDC_CAUSATION_ID)).contains("msg-incoming");
    }

    /** A send with no correlation in MDC (e.g. a scheduled sweeper) still gets a fresh UUID, so every message is traceable. */
    @Test
    void presend_without_mdc_generates_an_id() {
        Message out = message();

        interceptor.preSend(out);

        String id = out.getRequiredHeader(Correlation.MDC_CORRELATION_ID);
        assertThatCode(() -> UUID.fromString(id)).doesNotThrowAnyException();
    }

    /** On the consumer, preHandle puts correlationId, the message id and causationId into MDC for the handler's logs. */
    @Test
    void prehandle_restores_mdc_from_headers() {
        Message in = message();
        in.setHeader(Correlation.MDC_CORRELATION_ID, "corr-1");
        in.setHeader(Correlation.MDC_CAUSATION_ID, "msg-parent");

        interceptor.preHandle("sub", in);

        assertThat(MDC.get(Correlation.MDC_CORRELATION_ID)).isEqualTo("corr-1");
        assertThat(MDC.get(Correlation.MDC_MESSAGE_ID)).isEqualTo(in.getId());
        assertThat(MDC.get(Correlation.MDC_CAUSATION_ID)).isEqualTo("msg-parent");
    }

    /** A message from a producer without the interceptor (no header) still gets a correlation id in MDC. */
    @Test
    void prehandle_without_header_generates_an_id() {
        interceptor.preHandle("sub", message());

        assertThatCode(() -> UUID.fromString(MDC.get(Correlation.MDC_CORRELATION_ID))).doesNotThrowAnyException();
    }

    /** postHandle clears all three keys so the consumer thread doesn't carry them into the next message. */
    @Test
    void posthandle_clears_mdc() {
        Message in = message();
        in.setHeader(Correlation.MDC_CORRELATION_ID, "corr-1");
        in.setHeader(Correlation.MDC_CAUSATION_ID, "msg-parent");
        interceptor.preHandle("sub", in);

        interceptor.postHandle("sub", in, null);

        assertThat(MDC.get(Correlation.MDC_CORRELATION_ID)).isNull();
        assertThat(MDC.get(Correlation.MDC_MESSAGE_ID)).isNull();
        assertThat(MDC.get(Correlation.MDC_CAUSATION_ID)).isNull();
    }

    /** End to end over one hop: what preSend stamps is exactly what preHandle restores on the other side. */
    @Test
    void correlation_survives_send_then_handle() {
        MDC.put(Correlation.MDC_CORRELATION_ID, "corr-e2e");
        Message out = message();
        interceptor.preSend(out);
        MDC.clear();   // different service / thread

        interceptor.preHandle("sub", out);

        assertThat(MDC.get(Correlation.MDC_CORRELATION_ID)).isEqualTo("corr-e2e");
    }
}
