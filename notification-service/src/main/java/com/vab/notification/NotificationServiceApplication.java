package com.vab.notification;

import com.vab.notification.consumer.NotificationEventConsumer;
import io.eventuate.tram.events.subscriber.DomainEventDispatcher;
import io.eventuate.tram.events.subscriber.DomainEventDispatcherFactory;
import io.eventuate.tram.spring.consumer.common.TramConsumerCommonConfiguration;
import io.eventuate.tram.spring.consumer.kafka.EventuateTramKafkaMessageConsumerConfiguration;
import io.eventuate.tram.spring.events.subscriber.TramEventSubscriberConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Notification Service — a pure event-driven consumer.
 *
 * <p>Subscribes to Order domain events (relayed from the Tram outbox to Kafka by
 * Eventuate CDC) and dispatches subscriber notifications. It sends no commands
 * and owns no order/inventory/billing state — the event is the trigger.
 *
 * <p>No saga starter here, so the Tram consumer transport is imported explicitly:
 * Kafka message consumer + common consumer (dedupe) + domain-event subscriber.
 */
@SpringBootApplication
@Import({EventuateTramKafkaMessageConsumerConfiguration.class,
         TramConsumerCommonConfiguration.class,
         TramEventSubscriberConfiguration.class})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }

    /**
     * Registers the event consumer as a Tram domain-event dispatcher.
     * The dispatcher id ("notificationService") is the Kafka consumer group —
     * distinct from the order projector's group, so both receive every event.
     */
    @Bean
    public DomainEventDispatcher notificationDomainEventDispatcher(
            NotificationEventConsumer consumer,
            DomainEventDispatcherFactory factory) {
        return factory.make("notificationService", consumer.domainEventHandlers());
    }
}
