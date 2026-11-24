package com.vab.messaging;

import io.eventuate.tram.consumer.common.MessageHandlerDecorator;
import io.eventuate.tram.consumer.common.MessageHandlerDecoratorChain;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.SubscriberIdAndMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Parks a message that lacks the {@code ID} or {@code DESTINATION} header, before any other decorator runs.
 * Eventuate's tracing decorator (order 0) reads {@code DESTINATION} outside its try block, so such a message
 * would otherwise kill the subscription on every restart, out of reach of {@link ResilientMessageHandlerDecorator}.
 * The outbox always sets both, so only a record written to Kafka some other way can trip this.
 */
public class MessageEnvelopeGuard implements MessageHandlerDecorator {

    static final int ORDER = -100;

    private static final Logger log = LoggerFactory.getLogger(MessageEnvelopeGuard.class);

    private final ParkedMessageStore parking;
    private final MessagingMetrics metrics;

    public MessageEnvelopeGuard(ParkedMessageStore parking, MessagingMetrics metrics) {
        this.parking = parking;
        this.metrics = metrics;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void accept(SubscriberIdAndMessage sm, MessageHandlerDecoratorChain chain) {
        Map<String, String> headers = sm.getMessage().getHeaders();
        String missing = headers == null || headers.get(Message.ID) == null ? Message.ID
                : headers.get(Message.DESTINATION) == null ? Message.DESTINATION : null;
        if (missing == null) {
            chain.invokeNext(sm);
            return;
        }
        // A park failure propagates: the subscription stops, but the message isn't lost.
        parking.park(sm.getSubscriberId(), sm.getMessage(), FailureClass.POISON,
                new IllegalArgumentException("Message has no " + missing + " header"), 0);
        metrics.parked(sm.getSubscriberId(), FailureClass.POISON);
        log.error("Parked malformed message (no {} header): subscriber={}", missing, sm.getSubscriberId());
    }
}
