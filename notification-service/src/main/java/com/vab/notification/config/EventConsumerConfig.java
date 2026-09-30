package com.vab.notification.config;

import com.vab.events.common.EventuateJackson;
import com.vab.notification.consumer.NotificationEventConsumer;
import io.eventuate.tram.events.subscriber.DomainEventDispatcher;
import io.eventuate.tram.events.subscriber.DomainEventDispatcherFactory;
import io.eventuate.tram.spring.consumer.common.TramConsumerCommonConfiguration;
import io.eventuate.tram.spring.consumer.kafka.EventuateTramKafkaMessageConsumerConfiguration;
import io.eventuate.tram.spring.events.subscriber.TramEventSubscriberConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Tram consumer transport for this pure event consumer: Kafka message consumer +
 * common consumer (dedupe) + domain-event subscriber, and the dispatcher that wires
 * {@link NotificationEventConsumer} to its Kafka consumer group.
 *
 * <p>No saga starter here, so the transport is imported explicitly. Kept out of
 * {@code NotificationServiceApplication} so test slices ({@code @DataJpaTest}) don't
 * start a Kafka consumer; slices exclude scanned {@code @Configuration} classes.
 */
@Configuration(proxyBeanMethods = false)
@Import({EventuateTramKafkaMessageConsumerConfiguration.class,
         TramConsumerCommonConfiguration.class,
         TramEventSubscriberConfiguration.class})
public class EventConsumerConfig {

    /**
     * Registers the event consumer as a Tram domain-event dispatcher.
     * The dispatcher id ("notificationService") is the Kafka consumer group —
     * distinct from the order projector's group, so both receive every event.
     */
    @Bean
    public DomainEventDispatcher notificationDomainEventDispatcher(
            NotificationEventConsumer consumer,
            DomainEventDispatcherFactory factory) {
        // Pure consumer: it never instantiates the events, so their Instant-module
        // static hook never fires. Register JavaTimeModule eagerly before consuming.
        EventuateJackson.register();
        return factory.make("notificationService", consumer.domainEventHandlers());
    }
}
