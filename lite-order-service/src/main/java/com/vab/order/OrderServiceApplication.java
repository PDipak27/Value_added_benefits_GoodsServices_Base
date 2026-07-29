package com.vab.order;

import com.vab.events.common.EventuateJackson;
import io.eventuate.tram.spring.events.publisher.TramEventsPublisherConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Lite order service entry point.
 *
 * <p>The Tram saga-orchestration starter auto-configures the JDBC/Kafka message
 * transport. We import the Tram domain-event publisher so the state-stored write
 * side can publish domain events through the outbox. The Mongo read-side
 * projectors (and their {@code TramEventSubscriberConfiguration}) are removed in
 * Lite — the read model is collapsed to the Postgres write model.
 */
@SpringBootApplication
@Import(TramEventsPublisherConfiguration.class)
public class OrderServiceApplication {

    public static void main(String[] args) {
        // Saga consumes Instant-bearing replies (e.g. InventoryReserved.reservedUntil)
        // it never instantiates, so their static register-hook never fires. Register
        // JavaTimeModule on Eventuate's JSonMapper before any reply is deserialized.
        EventuateJackson.register();
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
