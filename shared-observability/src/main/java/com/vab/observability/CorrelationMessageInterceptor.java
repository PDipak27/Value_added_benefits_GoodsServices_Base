package com.vab.observability;

import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.MessageInterceptor;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * §C2 B-2: carries the correlation id across the async Kafka/saga boundary that HTTP
 * tracing can't see.
 *
 * <ul>
 *   <li><b>preSend</b> (producer thread, still holds the MDC of whatever triggered the
 *       send — an HTTP request or a message being handled): stamp {@code correlationId}
 *       onto the outgoing message header, and {@code causationId} = the id of the
 *       message currently being handled (empty for an HTTP-originated first send).</li>
 *   <li><b>preHandle / postHandle</b> (consumer thread): restore the id from the header
 *       into MDC around the handler, then clear it.</li>
 * </ul>
 *
 * <p>The header is stamped at send time (synchronous with the trigger) and persisted in
 * the transactional outbox, so it survives the CDC relay to Kafka.
 */
public class CorrelationMessageInterceptor implements MessageInterceptor {

    @Override
    public void preSend(Message message) {
        String correlationId = MDC.get(Correlation.MDC_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        message.setHeader(Correlation.MDC_CORRELATION_ID, correlationId);

        String causationId = MDC.get(Correlation.MDC_MESSAGE_ID);
        if (causationId != null) {
            message.setHeader(Correlation.MDC_CAUSATION_ID, causationId);
        }
    }

    @Override
    public void preHandle(String subscriberId, Message message) {
        String correlationId = message.getHeader(Correlation.MDC_CORRELATION_ID)
                .orElseGet(() -> UUID.randomUUID().toString());
        MDC.put(Correlation.MDC_CORRELATION_ID, correlationId);
        MDC.put(Correlation.MDC_MESSAGE_ID, message.getId());
        message.getHeader(Correlation.MDC_CAUSATION_ID)
                .ifPresent(c -> MDC.put(Correlation.MDC_CAUSATION_ID, c));
    }

    @Override
    public void postHandle(String subscriberId, Message message, Throwable throwable) {
        MDC.remove(Correlation.MDC_CORRELATION_ID);
        MDC.remove(Correlation.MDC_CAUSATION_ID);
        MDC.remove(Correlation.MDC_MESSAGE_ID);
    }
}
