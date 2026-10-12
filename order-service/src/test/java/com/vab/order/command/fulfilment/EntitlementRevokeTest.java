package com.vab.order.command.fulfilment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vab.events.fulfilment.EntitlementRevokeFailed;
import com.vab.events.fulfilment.EntitlementRevoked;
import com.vab.events.fulfilment.RevokeEntitlementCommand;
import com.vab.order.command.domain.Order;
import com.vab.order.command.service.OrderCommandService;
import com.vab.order.saga.PlaceOrderSaga;
import io.eventuate.tram.commands.common.Command;
import io.eventuate.tram.commands.common.ReplyMessageHeaders;
import io.eventuate.tram.commands.producer.CommandProducer;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.consumer.MessageConsumer;
import io.eventuate.tram.messaging.consumer.MessageHandler;
import io.eventuate.tram.messaging.producer.MessageBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Admin entitlement revoke (Phase 3), order side: the RevokeEntitlementCommand carries what fulfilment
 * needs, an EntitlementRevoked reply marks the order's entitlement revoked, and a failure leaves it ACTIVE.
 */
@ExtendWith(MockitoExtension.class)
class EntitlementRevokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock CommandProducer commandProducer;
    @Mock MessageConsumer messageConsumer;
    @Mock OrderCommandService orderCommandService;

    private EntitlementRevoke revoke;

    @BeforeEach
    void setUp() {
        revoke = new EntitlementRevoke(commandProducer, messageConsumer, orderCommandService);
    }

    private MessageHandler subscribedHandler() {
        revoke.subscribe();
        ArgumentCaptor<MessageHandler> handler = ArgumentCaptor.forClass(MessageHandler.class);
        verify(messageConsumer).subscribe(anyString(), eq(Set.of(EntitlementRevoke.REPLY_CHANNEL)), handler.capture());
        return handler.getValue();
    }

    private static Message reply(Object body) throws Exception {
        return MessageBuilder.withPayload(JSON.writeValueAsString(body))
                .withHeader(ReplyMessageHeaders.REPLY_TYPE, body.getClass().getName())
                .build();
    }

    /** The revoke command goes to fulfilmentService with the order's productType and OTT externalRef, replying to the revoke channel. */
    @Test
    void revoke_sends_command_with_product_type_and_external_ref() {
        Order order = Order.place("ord-r", "sub-1", "OTT_NETFLIX_6M", "DIGITAL_SUBSCRIPTION", "px-1", 599, "INR", "PAY_NOW");
        order.complete(Instant.now(), null, null, "OTT-9");

        revoke.revoke(order);

        ArgumentCaptor<Command> cmd = ArgumentCaptor.forClass(Command.class);
        verify(commandProducer).send(eq(PlaceOrderSaga.FULFILMENT_CHANNEL), cmd.capture(),
                eq(EntitlementRevoke.REPLY_CHANNEL), eq(Map.of()));
        RevokeEntitlementCommand sent = (RevokeEntitlementCommand) cmd.getValue();
        assertThat(sent.getOrderId()).isEqualTo("ord-r");
        assertThat(sent.getProductType()).isEqualTo("DIGITAL_SUBSCRIPTION");
        assertThat(sent.getExternalRef()).isEqualTo("OTT-9");
    }

    /** An EntitlementRevoked reply applies the revoke to the order (which publishes OrderEntitlementRevoked). */
    @Test
    void revoked_reply_applies_the_revoke() throws Exception {
        subscribedHandler().accept(reply(new EntitlementRevoked("ord-r")));

        verify(orderCommandService).applyEntitlementRevoked("ord-r");
    }

    /** An EntitlementRevokeFailed reply changes nothing: the entitlement stays ACTIVE and the admin can retry. */
    @Test
    void failed_reply_leaves_entitlement_active() throws Exception {
        subscribedHandler().accept(reply(new EntitlementRevokeFailed("ord-r", "OTT_REVOKE_FAILED")));

        verifyNoInteractions(orderCommandService);
    }

    /** Any other reply type on the channel is ignored. */
    @Test
    void unexpected_reply_type_is_ignored() {
        subscribedHandler().accept(MessageBuilder.withPayload("{}")
                .withHeader(ReplyMessageHeaders.REPLY_TYPE, "com.example.Unexpected").build());

        verifyNoInteractions(orderCommandService);
    }
}
