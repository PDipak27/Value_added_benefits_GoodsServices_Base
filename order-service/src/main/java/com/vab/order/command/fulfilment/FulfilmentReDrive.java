package com.vab.order.command.fulfilment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vab.events.fulfilment.FulfilOrderCommand;
import com.vab.events.fulfilment.OrderFulfilled;
import com.vab.events.fulfilment.OrderProvisioningFailed;
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
 * Admin re-drive of a parked DIGITAL_SUBSCRIPTION (DD-27). Re-sends the same
 * {@link FulfilOrderCommand} the saga used, so fulfilment-service's {@code fulfil()}
 * handler runs its normal product-type dispatch — now against the fixed OTT service.
 *
 * <p>This is a plain Tram command/reply, <em>not</em> a saga and not a saga resume
 * (the original saga has long resolved). The reply ({@link OrderFulfilled} on
 * success, {@link OrderProvisioningFailed} on continued failure) is a success-outcome
 * reply carrying {@code orderId}, so this consumer correlates without saga state and
 * applies the outcome to the parked order.
 */
@Component
public class FulfilmentReDrive {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentReDrive.class);

    /** Reply destination owned by this service; the re-drive command points its replyTo here. */
    static final String REPLY_CHANNEL = "orderService-fulfilment-replies";
    private static final String SUBSCRIBER_ID = "orderServiceFulfilmentReplies";

    private final CommandProducer     commandProducer;
    private final MessageConsumer     messageConsumer;
    private final OrderCommandService orderCommandService;
    private final ObjectMapper        json = new ObjectMapper()
            .registerModule(new JavaTimeModule())   // OrderFulfilled carries Instant validFrom/validUntil
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public FulfilmentReDrive(CommandProducer commandProducer,
                             MessageConsumer messageConsumer,
                             @Lazy OrderCommandService orderCommandService) {
        this.commandProducer     = commandProducer;
        this.messageConsumer     = messageConsumer;
        this.orderCommandService = orderCommandService;
    }

    // Subscribe only once the context is fully built — NOT @PostConstruct. Because
    // OrderCommandService depends on this bean, @PostConstruct would start the consumer
    // before OrderCommandService exists; a backlogged reply could then invoke the @Lazy
    // proxy mid-creation → BeanCurrentlyInCreationException → the swimlane terminates and
    // the consumer leaves the group for good.
    @EventListener(ApplicationReadyEvent.class)
    void subscribe() {
        messageConsumer.subscribe(SUBSCRIBER_ID, Set.of(REPLY_CHANNEL), this::onReply);
    }

    /**
     * Re-send the fulfil command for a parked order. The send joins the caller's
     * transaction via the Tram command outbox; the order is completed (or re-parked)
     * asynchronously when the reply arrives.
     */
    public void reDrive(Order order) {
        FulfilOrderCommand cmd = new FulfilOrderCommand(order.getId(), order.getSubscriberId(),
                order.getOfferCode(), order.getProductType(), order.getActivationKey(), order.getTermMonths());
        commandProducer.send(PlaceOrderSaga.FULFILMENT_CHANNEL, cmd, REPLY_CHANNEL, Map.of());
        log.info("Re-drive command sent: orderId={}, productType={}", order.getId(), order.getProductType());
    }

    private void onReply(Message message) {
        String replyType = message.getRequiredHeader(ReplyMessageHeaders.REPLY_TYPE);
        if (OrderFulfilled.class.getName().equals(replyType)) {
            OrderFulfilled r = read(message, OrderFulfilled.class);
            log.info("Re-drive reply OK: orderId={}, externalRef={}", r.getOrderId(), r.getExternalRef());
            orderCommandService.completeFromReDrive(r.getOrderId(), r.getExternalRef(),
                    r.getValidFrom(), r.getValidUntil());
        } else if (OrderProvisioningFailed.class.getName().equals(replyType)) {
            OrderProvisioningFailed r = read(message, OrderProvisioningFailed.class);
            log.warn("Re-drive reply FAILED (stays parked): orderId={}, reason={}", r.getOrderId(), r.getReason());
            orderCommandService.parkFromReDrive(r.getOrderId(), r.getReason() + ": " + r.getDetail());
        } else {
            // Only DIGITAL_SUBSCRIPTION parks, so fulfil() replies OrderFulfilled/OrderProvisioningFailed.
            log.warn("Re-drive reply ignored (unexpected type {}): {}", replyType, message.getPayload());
        }
    }

    private <T> T read(Message message, Class<T> type) {
        try {
            return json.readValue(message.getPayload(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse re-drive reply " + type.getSimpleName(), e);
        }
    }
}
