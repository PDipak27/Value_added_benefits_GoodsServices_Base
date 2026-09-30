package com.vab.order.config;

import com.vab.order.query.projection.OrderProjector;
import com.vab.order.query.projection.OrderSearchProjector;
import io.eventuate.tram.events.subscriber.DomainEventDispatcher;
import io.eventuate.tram.events.subscriber.DomainEventDispatcherFactory;
import io.eventuate.tram.spring.events.publisher.TramEventsPublisherConfiguration;
import io.eventuate.tram.spring.events.subscriber.TramEventSubscriberConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Tram domain events: the publisher (write side → outbox) and the two read-side
 * projector dispatchers (Kafka → Mongo).
 *
 * <p>Kept out of {@code OrderServiceApplication} on purpose: annotations and
 * {@code @Bean} methods on the main class are applied to <em>every</em> test slice
 * (e.g. {@code @DataJpaTest}), which has no Kafka {@code MessageConsumer} and no
 * Mongo projectors. Test slices exclude scanned {@code @Configuration} classes, so
 * here it only loads in the full application context.
 */
@Configuration(proxyBeanMethods = false)
@Import({TramEventsPublisherConfiguration.class, TramEventSubscriberConfiguration.class})
public class DomainEventsConfig {

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

    /**
     * Ops-search projector as a second, independent Tram domain-event dispatcher
     * (§B3). Its own dispatcher id ("orderSearchProjector") is a distinct Kafka
     * consumer group, so order_search_v1 is built from the same event stream as
     * orders_v1 but consumed and rebuildable independently.
     */
    @Bean
    public DomainEventDispatcher orderSearchDomainEventDispatcher(
            OrderSearchProjector projector,
            DomainEventDispatcherFactory factory) {
        return factory.make("orderSearchProjector", projector.domainEventHandlers());
    }
}
