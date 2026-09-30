package com.vab.order;

import com.vab.events.common.EventuateJackson;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Order service entry point.
 *
 * <p>The Tram saga-orchestration starter auto-configures the JDBC/Kafka message
 * transport. Domain-event publishing and the projector dispatchers live in
 * {@link com.vab.order.config.DomainEventsConfig} — deliberately not on this class,
 * so test slices such as {@code @DataJpaTest} don't inherit them.
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        // Saga consumes Instant-bearing replies (e.g. InventoryReserved.reservedUntil)
        // it never instantiates, so their static register-hook never fires. Register
        // JavaTimeModule on Eventuate's JSonMapper before any reply is deserialized.
        EventuateJackson.register();
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
