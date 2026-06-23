package com.vab.notification.consumer;

import com.vab.events.order.OrderCancelled;
import com.vab.events.order.OrderCancelledRefunded;
import com.vab.events.order.OrderCompleted;
import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderFailed;
import com.vab.events.order.OrderFulfilmentFailed;
import com.vab.notification.dispatch.Channel;
import com.vab.notification.dispatch.NotificationDispatcher;
import com.vab.notification.dispatch.NotificationRouter;
import com.vab.notification.dispatch.NotificationType;
import com.vab.notification.domain.DeliveryRecord;
import com.vab.notification.domain.DeliveryRecordRepository;
import com.vab.notification.template.NotificationTemplates;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import io.eventuate.tram.events.subscriber.DomainEventHandlers;
import io.eventuate.tram.events.subscriber.DomainEventHandlersBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes Order domain events and emits subscriber notifications.
 *
 * <p>Subscribes to the Order aggregate's event stream. The aggregate type string
 * must match the publisher ({@code com.vab.order.command.domain.Order}); it is
 * duplicated here as a literal because the Order class lives in another service.
 *
 * <p>Idempotency: the Tram {@code received_messages} table short-circuits
 * redelivered events before a handler runs.
 */
@Component
public class NotificationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventConsumer.class);

    /** Must equal {@code com.vab.order.command.domain.Order.AGGREGATE_TYPE}. */
    private static final String ORDER_AGGREGATE_TYPE = "com.vab.order.command.domain.Order";

    private final NotificationTemplates    templates;
    private final NotificationRouter       router;
    private final NotificationDispatcher   dispatcher;
    private final DeliveryRecordRepository deliveryRepo;
    /** Ops-desk address for backoffice alerts (DD-27); subscriber-facing types ignore it. */
    private final String                   adminRecipient;

    public NotificationEventConsumer(NotificationTemplates templates,
                                     NotificationRouter router,
                                     NotificationDispatcher dispatcher,
                                     DeliveryRecordRepository deliveryRepo,
                                     @org.springframework.beans.factory.annotation.Value(
                                             "${notification.admin-recipient}") String adminRecipient) {
        this.templates      = templates;
        this.router         = router;
        this.dispatcher     = dispatcher;
        this.deliveryRepo   = deliveryRepo;
        this.adminRecipient = adminRecipient;
    }

    public DomainEventHandlers domainEventHandlers() {
        return DomainEventHandlersBuilder
                .forAggregateType(ORDER_AGGREGATE_TYPE)
                .onEvent(OrderConfirmed.class, this::onOrderConfirmed)
                .onEvent(OrderCompleted.class, this::onOrderCompleted)
                .onEvent(OrderCancelled.class, this::onOrderCancelled)
                .onEvent(OrderCancelledRefunded.class, this::onOrderCancelledRefunded)
                .onEvent(OrderFailed.class, this::onOrderFailed)
                .onEvent(OrderFulfilmentFailed.class, this::onOrderFulfilmentFailed)
                .build();
    }

    @Transactional
    public void onOrderConfirmed(DomainEventEnvelope<OrderConfirmed> de) {
        String orderId = de.getAggregateId();
        OrderConfirmed event = de.getEvent();
        log.info("Received OrderConfirmed: orderId={}, productType={}", orderId, event.getProductType());
        // Lean intermediate milestone — no artifact yet (it arrives on completion).
        notify(NotificationType.ORDER_CONFIRMED, orderId, Map.of("orderId", orderId));
    }

    @Transactional
    public void onOrderCompleted(DomainEventEnvelope<OrderCompleted> de) {
        String orderId = de.getAggregateId();
        OrderCompleted event = de.getEvent();
        log.info("Received OrderCompleted: orderId={}, productType={}", orderId, event.getProductType());

        // Product-type aware copy naming the delivered artifact (DD-23).
        Map<String, String> vars = new HashMap<>();
        vars.put("orderId", orderId);
        vars.put("trackingRef",   nullToEmpty(event.getTrackingRef()));
        vars.put("activationKey", nullToEmpty(event.getActivationKey()));
        vars.put("externalRef",   nullToEmpty(event.getExternalRef()));

        String body = templates.renderCompletion(event.getProductType(), vars);
        deliver(NotificationType.ORDER_COMPLETED, orderId, body);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    @Transactional
    public void onOrderCancelled(DomainEventEnvelope<OrderCancelled> de) {
        String orderId = de.getAggregateId();
        OrderCancelled event = de.getEvent();
        log.info("Received OrderCancelled: orderId={}, reason={}", orderId, event.getReason());
        // DD-26: cancelled before the pivot — nothing was charged.
        notify(NotificationType.ORDER_CANCELLED, orderId,
                Map.of("orderId", orderId, "reason", String.valueOf(event.getReason())));
    }

    @Transactional
    public void onOrderCancelledRefunded(DomainEventEnvelope<OrderCancelledRefunded> de) {
        String orderId = de.getAggregateId();
        OrderCancelledRefunded event = de.getEvent();
        log.info("Received OrderCancelledRefunded: orderId={}, reason={}", orderId, event.getReason());
        // DD-26: unwound after the pivot via forward-recovery — charge refunded/reversed.
        notify(NotificationType.ORDER_CANCELLED_REFUNDED, orderId,
                Map.of("orderId", orderId, "reason", String.valueOf(event.getReason())));
    }

    @Transactional
    public void onOrderFailed(DomainEventEnvelope<OrderFailed> de) {
        String orderId = de.getAggregateId();
        OrderFailed event = de.getEvent();
        log.info("Received OrderFailed: orderId={}, failedStep={}", orderId, event.getFailedStep());
        notify(NotificationType.ORDER_FAILED, orderId,
                Map.of("orderId", orderId, "reason", String.valueOf(event.getTerminalReason())));
    }

    @Transactional
    public void onOrderFulfilmentFailed(DomainEventEnvelope<OrderFulfilmentFailed> de) {
        String orderId = de.getAggregateId();
        OrderFulfilmentFailed event = de.getEvent();
        log.info("Received OrderFulfilmentFailed: orderId={}, failedStep={}", orderId, event.getFailedStep());
        // DD-27: backoffice/admin alert — OTT provisioning failing, order parked for re-drive.
        notify(NotificationType.ORDER_FULFILMENT_FAILED, orderId,
                Map.of("orderId", orderId, "reason", String.valueOf(event.getReason())));
    }

    // ── Render → route → dispatch → record ──────────────────────────────────

    private void notify(NotificationType type, String orderId, Map<String, String> vars) {
        deliver(type, orderId, templates.render(type, vars));
    }

    private void deliver(NotificationType type, String orderId, String body) {
        Channel channel = router.routeFor(type);
        // Backoffice alerts go to the ops desk; subscriber-facing sends key on the
        // orderId (real subscriber → phone/email lookup is out of scope for the stub).
        String recipient = type.isBackoffice() ? adminRecipient : "order:" + orderId;

        String providerRef = dispatcher.dispatch(channel, recipient, body);

        deliveryRepo.save(new DeliveryRecord(
                "dlv_" + UUID.randomUUID(), orderId, type, channel,
                DeliveryRecord.Status.SENT, recipient, providerRef, body));
        log.info("Delivery recorded: orderId={}, type={}, channel={}, ref={}",
                orderId, type, channel, providerRef);
    }
}
