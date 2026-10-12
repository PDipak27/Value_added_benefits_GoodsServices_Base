package com.vab.order.command.fulfilment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vab.events.fulfilment.FulfilOrderCommand;
import com.vab.events.fulfilment.OrderFulfilled;
import com.vab.events.fulfilment.OrderProvisioningFailed;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * DD-27 admin re-drive, order side: the re-sent FulfilOrderCommand mirrors the saga's, and the
 * async reply on the private reply channel completes (OrderFulfilled) or re-parks
 * (OrderProvisioningFailed) the order. The Tram consumer is mocked; replies are fed to the captured handler.
 */
@ExtendWith(MockitoExtension.class)
class FulfilmentReDriveTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock CommandProducer commandProducer;
    @Mock MessageConsumer messageConsumer;
    @Mock OrderCommandService orderCommandService;

    private FulfilmentReDrive reDrive;

    @BeforeEach
    void setUp() {
        reDrive = new FulfilmentReDrive(commandProducer, messageConsumer, orderCommandService);
    }

    /** Subscribes (on context-ready) and returns the handler Tram would call for replies. */
    private MessageHandler subscribedHandler() {
        reDrive.subscribe();
        ArgumentCaptor<MessageHandler> handler = ArgumentCaptor.forClass(MessageHandler.class);
        verify(messageConsumer).subscribe(anyString(), eq(Set.of(FulfilmentReDrive.REPLY_CHANNEL)), handler.capture());
        return handler.getValue();
    }

    private static Message reply(Object body) throws Exception {
        return MessageBuilder.withPayload(JSON.writeValueAsString(body))
                .withHeader(ReplyMessageHeaders.REPLY_TYPE, body.getClass().getName())
                .build();
    }

    /** The re-drive re-sends the saga's fulfil command (same order data incl. term) to fulfilmentService, replying to the re-drive channel. */
    @Test
    void redrive_resends_original_fulfil_command() {
        Order order = Order.place("ord-p", "sub-1", "OTT_NETFLIX_6M", "DIGITAL_SUBSCRIPTION", "px-1", 599, "INR", "PAY_NOW");
        order.setTermMonths(6);

        reDrive.reDrive(order);

        ArgumentCaptor<Command> cmd = ArgumentCaptor.forClass(Command.class);
        verify(commandProducer).send(eq(PlaceOrderSaga.FULFILMENT_CHANNEL), cmd.capture(),
                eq(FulfilmentReDrive.REPLY_CHANNEL), eq(Map.of()));
        FulfilOrderCommand sent = (FulfilOrderCommand) cmd.getValue();
        assertThat(sent.getOrderId()).isEqualTo("ord-p");
        assertThat(sent.getSubscriberId()).isEqualTo("sub-1");
        assertThat(sent.getOfferCode()).isEqualTo("OTT_NETFLIX_6M");
        assertThat(sent.getProductType()).isEqualTo("DIGITAL_SUBSCRIPTION");
        assertThat(sent.getTermMonths()).isEqualTo(6);
    }

    /** An OrderFulfilled reply completes the parked order with the provisioned externalRef and validity window. */
    @Test
    void success_reply_completes_the_order() throws Exception {
        Instant from = Instant.parse("2026-10-01T00:00:00Z"), until = Instant.parse("2027-04-01T00:00:00Z");

        subscribedHandler().accept(reply(new OrderFulfilled("ord-p", "DIGITAL_SUBSCRIPTION", "ent_1",
                null, null, "OTT-RD", from, until)));

        verify(orderCommandService).completeFromReDrive("ord-p", "OTT-RD", from, until);
    }

    /** An OrderProvisioningFailed reply keeps the order parked, recording "reason: detail". */
    @Test
    void failure_reply_re_parks_the_order() throws Exception {
        subscribedHandler().accept(reply(new OrderProvisioningFailed("ord-p", "PROVISIONING_UNAVAILABLE", "HTTP 503")));

        verify(orderCommandService).parkFromReDrive("ord-p", "PROVISIONING_UNAVAILABLE: HTTP 503");
    }

    /** Any other reply type on the channel is ignored — the order is not touched. */
    @Test
    void unexpected_reply_type_is_ignored() {
        Message other = MessageBuilder.withPayload("{}")
                .withHeader(ReplyMessageHeaders.REPLY_TYPE, "com.example.Unexpected").build();

        subscribedHandler().accept(other);

        verifyNoInteractions(orderCommandService);
        verify(commandProducer, org.mockito.Mockito.never()).send(anyString(), any(Command.class), anyString(), any());
    }
}
