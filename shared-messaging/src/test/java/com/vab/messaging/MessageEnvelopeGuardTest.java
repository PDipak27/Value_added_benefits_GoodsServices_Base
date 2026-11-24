package com.vab.messaging;

import io.eventuate.tram.consumer.common.MessageHandlerDecoratorChain;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.MessageImpl;
import io.eventuate.tram.messaging.common.SubscriberIdAndMessage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MessageEnvelopeGuardTest {

    private static final String SUBSCRIBER = "inventoryCommandDispatcher";

    private final ParkedMessageStore parking = mock(ParkedMessageStore.class);
    private final MessagingMetrics metrics = mock(MessagingMetrics.class);
    private final MessageEnvelopeGuard guard = new MessageEnvelopeGuard(parking, metrics);
    private final MessageHandlerDecoratorChain chain = mock(MessageHandlerDecoratorChain.class);

    private static SubscriberIdAndMessage message(String... headerPairs) {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < headerPairs.length; i += 2) headers.put(headerPairs[i], headerPairs[i + 1]);
        return new SubscriberIdAndMessage(SUBSCRIBER, new MessageImpl("{}", headers));
    }

    @Test
    void well_formed_message_passes_through() {
        SubscriberIdAndMessage sm = message(Message.ID, "m-1", Message.DESTINATION, "inventoryService");

        guard.accept(sm, chain);

        verify(chain).invokeNext(sm);
        verifyNoInteractions(parking);
    }

    @Test
    void message_without_destination_is_parked_instead_of_reaching_tracing() {
        SubscriberIdAndMessage sm = message(Message.ID, "m-1");

        guard.accept(sm, chain);

        verify(chain, never()).invokeNext(any());
        verify(parking).park(eq(SUBSCRIBER), eq(sm.getMessage()), eq(FailureClass.POISON), any(), eq(0));
        verify(metrics).parked(SUBSCRIBER, FailureClass.POISON);
    }

    @Test
    void message_without_id_is_parked() {
        guard.accept(message(Message.DESTINATION, "inventoryService"), chain);

        verify(chain, never()).invokeNext(any());
        verify(parking).park(eq(SUBSCRIBER), any(), eq(FailureClass.POISON), any(), anyInt());
    }

    @Test
    void park_failure_propagates_so_the_message_is_not_lost() {
        doThrow(new IllegalStateException("db down")).when(parking).park(any(), any(), any(), any(), anyInt());

        assertThatThrownBy(() -> guard.accept(message(Message.ID, "m-1"), chain))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runs_before_eventuates_tracing_decorator() {
        assertThat(guard.getOrder()).isLessThan(0);
    }
}
