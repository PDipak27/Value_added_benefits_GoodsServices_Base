package com.vab.notification.consumer;

import com.vab.events.order.OrderFulfilmentFailed;
import com.vab.notification.dispatch.Channel;
import com.vab.notification.dispatch.NotificationDispatcher;
import com.vab.notification.dispatch.NotificationRouter;
import com.vab.notification.dispatch.NotificationType;
import com.vab.notification.domain.DeliveryRecord;
import com.vab.notification.domain.DeliveryRecordRepository;
import com.vab.notification.template.NotificationTemplates;
import io.eventuate.tram.events.common.DomainEvent;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DD-27 admin-alert path against a real Postgres (Testcontainers): an
 * {@code OrderFulfilmentFailed} domain event drives the render → route →
 * dispatch → persist chain, and the delivery is written as an EMAIL to the ops
 * desk (not the subscriber). The real templates/router/dispatcher beans run; only
 * the event envelope is stubbed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({NotificationTemplates.class, NotificationRouter.class,
        NotificationDispatcher.class, NotificationEventConsumer.class})
@TestPropertySource(properties = "notification.admin-recipient=ops-desk@vab.example")
class NotificationDeliveryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired NotificationEventConsumer consumer;
    @Autowired DeliveryRecordRepository deliveryRepo;

    @SuppressWarnings("unchecked")
    private static <E extends DomainEvent> DomainEventEnvelope<E> envelope(String orderId, E event) {
        DomainEventEnvelope<E> de = mock(DomainEventEnvelope.class);
        when(de.getAggregateId()).thenReturn(orderId);
        when(de.getEvent()).thenReturn(event);
        return de;
    }

    @Test
    void provisioning_failed_persists_admin_email_alert() {
        consumer.onOrderFulfilmentFailed(envelope("ord-it-park",
                new OrderFulfilmentFailed(Instant.now(), 3, "FULFIL_PROVISION",
                        "PROVISIONING_UNAVAILABLE: 503")));

        List<DeliveryRecord> saved = deliveryRepo.findByType(NotificationType.ORDER_FULFILMENT_FAILED);
        assertThat(saved).hasSize(1);
        DeliveryRecord r = saved.get(0);
        assertThat(r.getOrderId()).isEqualTo("ord-it-park");
        assertThat(r.getChannel()).isEqualTo(Channel.EMAIL);
        assertThat(r.getRecipient()).isEqualTo("ops-desk@vab.example");   // notify ADMIN
        assertThat(r.getStatus()).isEqualTo(DeliveryRecord.Status.SENT);
        assertThat(r.getBody()).contains("ACTION REQUIRED");
    }
}
