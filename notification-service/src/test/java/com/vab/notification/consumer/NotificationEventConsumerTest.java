package com.vab.notification.consumer;

import com.vab.events.order.OrderCancelled;
import com.vab.events.order.OrderCancelledRefunded;
import com.vab.events.order.OrderCompleted;
import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderFailed;
import com.vab.notification.dispatch.Channel;
import com.vab.notification.dispatch.NotificationDispatcher;
import com.vab.notification.dispatch.NotificationRouter;
import com.vab.notification.dispatch.NotificationType;
import com.vab.notification.domain.DeliveryRecord;
import com.vab.notification.domain.DeliveryRecordRepository;
import com.vab.notification.template.NotificationTemplates;
import io.eventuate.tram.events.common.DomainEvent;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests the order-event → notification flow. The dispatcher and delivery
 * repository are mocked; the {@link NotificationTemplates} and
 * {@link NotificationRouter} are <em>real</em> so we verify the actual rendered
 * body and chosen channel end up on the persisted {@link DeliveryRecord}.
 */
@ExtendWith(MockitoExtension.class)
class NotificationEventConsumerTest {

    @Mock NotificationDispatcher dispatcher;
    @Mock DeliveryRecordRepository deliveryRepo;

    private NotificationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new NotificationEventConsumer(
                new NotificationTemplates(), new NotificationRouter(), dispatcher, deliveryRepo);
        when(dispatcher.dispatch(any(Channel.class), anyString(), anyString())).thenReturn("msg_x");
    }

    @SuppressWarnings("unchecked")
    private static <E extends DomainEvent> DomainEventEnvelope<E> envelope(String orderId, E event) {
        DomainEventEnvelope<E> de = mock(DomainEventEnvelope.class);
        when(de.getAggregateId()).thenReturn(orderId);
        when(de.getEvent()).thenReturn(event);
        return de;
    }

    private List<DeliveryRecord> savedRecords(int times) {
        ArgumentCaptor<DeliveryRecord> c = ArgumentCaptor.forClass(DeliveryRecord.class);
        verify(deliveryRepo, times(times)).save(c.capture());
        return c.getAllValues();
    }

    @Test
    void order_confirmed_sends_one_push() {
        consumer.onOrderConfirmed(envelope("ord-1",
                new OrderConfirmed(Instant.now(), 1, "PHYSICAL_GOOD")));

        DeliveryRecord r = savedRecords(1).get(0);
        assertThat(r.getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(r.getChannel()).isEqualTo(Channel.PUSH);
        assertThat(r.getBody()).contains("ord-1").contains("confirmed");
    }

    @Test
    void order_completed_renders_product_type_specific_artifact() {
        consumer.onOrderCompleted(envelope("ord-1",
                new OrderCompleted(Instant.now(), 5, "PHYSICAL_GOOD", "TRK9", null, null)));

        DeliveryRecord r = savedRecords(1).get(0);
        assertThat(r.getType()).isEqualTo(NotificationType.ORDER_COMPLETED);
        assertThat(r.getBody()).contains("TRK9"); // tracking ref named for a physical good
    }

    @Test
    void order_cancelled_sends_one_sms_not_charged() {
        consumer.onOrderCancelled(envelope("ord-1",
                new OrderCancelled(Instant.now(), 6, "USER_CANCEL: before pivot")));

        // DD-26: pre-pivot cancel — one user SMS, nothing charged.
        DeliveryRecord r = savedRecords(1).get(0);
        assertThat(r.getType()).isEqualTo(NotificationType.ORDER_CANCELLED);
        assertThat(r.getChannel()).isEqualTo(Channel.SMS);
        assertThat(r.getBody()).contains("ord-1").contains("not been charged");
    }

    @Test
    void order_cancelled_refunded_sends_one_sms_refunded() {
        consumer.onOrderCancelledRefunded(envelope("ord-1",
                new OrderCancelledRefunded(Instant.now(), 7, "FULFIL_FAILED: DELIVERY_FAILED")));

        // DD-26: post-pivot forward-recovery — one user SMS, refunded/reversed.
        DeliveryRecord r = savedRecords(1).get(0);
        assertThat(r.getType()).isEqualTo(NotificationType.ORDER_CANCELLED_REFUNDED);
        assertThat(r.getChannel()).isEqualTo(Channel.SMS);
        assertThat(r.getBody()).contains("ord-1").contains("refunded");
    }

    @Test
    void order_failed_sends_one_sms_with_reason() {
        consumer.onOrderFailed(envelope("ord-1",
                new OrderFailed("RESERVE_INVENTORY", "OUT_OF_STOCK", 2)));

        DeliveryRecord r = savedRecords(1).get(0);
        assertThat(r.getChannel()).isEqualTo(Channel.SMS);
        assertThat(r.getBody()).contains("OUT_OF_STOCK");
    }
}
