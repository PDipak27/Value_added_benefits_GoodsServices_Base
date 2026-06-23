package com.vab.order;

import com.vab.events.common.EventuateJackson;
import com.vab.order.query.projection.OrderProjector;
import io.eventuate.tram.events.subscriber.DomainEventDispatcher;
import io.eventuate.tram.events.subscriber.DomainEventDispatcherFactory;
import io.eventuate.tram.spring.events.publisher.TramEventsPublisherConfiguration;
import io.eventuate.tram.spring.events.subscriber.TramEventSubscriberConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Order service entry point (post-DD-14).
 *
 * <p>The Tram saga-orchestration starter auto-configures the JDBC/Kafka message
 * transport. We additionally import the Tram domain-event publisher and
 * subscriber configurations so the state-stored write side can publish domain
 * events through the outbox and the projector can consume them.
 */
@SpringBootApplication
@Import({TramEventsPublisherConfiguration.class, TramEventSubscriberConfiguration.class})
public class OrderServiceApplication {

    public static void main(String[] args) {
        // Saga consumes Instant-bearing replies (e.g. InventoryReserved.reservedUntil)
        // it never instantiates, so their static register-hook never fires. Register
        // JavaTimeModule on Eventuate's JSonMapper before any reply is deserialized.
        EventuateJackson.register();
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    /**
     * Registers the read-model projector as a Tram domain-event dispatcher.
     * The dispatcher id ("orderServiceProjector") is the Kafka consumer group.
     */
    @Bean
    public DomainEventDispatcher orderDomainEventDispatcher(
            OrderProjector projector,
            DomainEventDispatcherFactory factory) {
        return factory.make("orderServiceProjector", projector.domainEventHandlers());
    }
}
