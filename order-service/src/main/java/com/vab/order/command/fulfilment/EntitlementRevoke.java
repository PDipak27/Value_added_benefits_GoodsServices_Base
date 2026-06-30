package com.vab.order.command.fulfilment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vab.events.fulfilment.EntitlementRevokeFailed;
import com.vab.events.fulfilment.EntitlementRevoked;
import com.vab.events.fulfilment.RevokeEntitlementCommand;
import com.vab.order.command.domain.Order;
import com.vab.order.command.service.OrderCommandService;
import com.vab.order.saga.PlaceOrderSaga;
import io.eventuate.tram.commands.common.ReplyMessageHeaders;
import io.eventuate.tram.commands.producer.CommandProducer;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.consumer.MessageConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Admin entitlement revoke (Phase 3 / backlog A4). Sends a {@link RevokeEntitlementCommand}
 * to fulfilment-service (which calls OTT {@code DELETE} for DIGITAL_SUBSCRIPTION) and applies
 * the reply to the order asynchronously — a plain Tram command/reply that mirrors the DD-27
 * re-drive, not a saga. On success the order's entitlement is marked revoked and the read
 * model flips to REVOKED; on failure the entitlement stays ACTIVE and the admin can retry.
 */
@Component
public class EntitlementRevoke {

    private static final Logger log = LoggerFactory.getLogger(EntitlementRevoke.class);

    static final String REPLY_CHANNEL = "orderService-revoke-replies";
    private static final String SUBSCRIBER_ID = "orderServiceRevokeReplies";

    private final CommandProducer     commandProducer;
    private final MessageConsumer     messageConsumer;
    private final OrderCommandService orderCommandService;
    private final ObjectMapper        json = new ObjectMapper()
            .registerModule(new JavaTimeModule())   // consistency: replies may carry Instant fields
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public EntitlementRevoke(CommandProducer commandProducer,
                             MessageConsumer messageConsumer,
                             @Lazy OrderCommandService orderCommandService) {
        this.commandProducer     = commandProducer;
        this.messageConsumer     = messageConsumer;
        this.orderCommandService = orderCommandService;
    }

    // Subscribe on context-ready, not @PostConstruct: OrderCommandService depends on this
    // bean, so an earlier subscription could process a reply before that bean exists and
    // hit BeanCurrentlyInCreationException on the @Lazy proxy (see FulfilmentReDrive).
    @EventListener(ApplicationReadyEvent.class)
    void subscribe() {
        messageConsumer.subscribe(SUBSCRIBER_ID, Set.of(REPLY_CHANNEL), this::onReply);
    }

    /** Send the revoke command for a completed benefit order; applied when the reply lands. */
    public void revoke(Order order) {
        RevokeEntitlementCommand cmd = new RevokeEntitlementCommand(
                order.getId(), order.getProductType(), order.getExternalRef());
        commandProducer.send(PlaceOrderSaga.FULFILMENT_CHANNEL, cmd, REPLY_CHANNEL, Map.of());
        log.info("Revoke command sent: orderId={}, productType={}", order.getId(), order.getProductType());
    }

    private void onReply(Message message) {
        String replyType = message.getRequiredHeader(ReplyMessageHeaders.REPLY_TYPE);
        if (EntitlementRevoked.class.getName().equals(replyType)) {
            EntitlementRevoked r = read(message, EntitlementRevoked.class);
            log.info("Revoke reply OK: orderId={}", r.getOrderId());
            orderCommandService.applyEntitlementRevoked(r.getOrderId());
        } else if (EntitlementRevokeFailed.class.getName().equals(replyType)) {
            EntitlementRevokeFailed r = read(message, EntitlementRevokeFailed.class);
            log.warn("Revoke reply FAILED (entitlement stays ACTIVE): orderId={}, reason={}",
                    r.getOrderId(), r.getReason());
        } else {
            log.warn("Revoke reply ignored (unexpected type {}): {}", replyType, message.getPayload());
        }
    }

    private <T> T read(Message message, Class<T> type) {
        try {
            return json.readValue(message.getPayload(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse revoke reply " + type.getSimpleName(), e);
        }
    }
}
