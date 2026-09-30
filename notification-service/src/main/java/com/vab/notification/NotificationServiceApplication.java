package com.vab.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Notification Service — a pure event-driven consumer.
 *
 * <p>Subscribes to Order domain events (relayed from the Tram outbox to Kafka by
 * Eventuate CDC) and dispatches subscriber notifications. It sends no commands
 * and owns no order/inventory/billing state — the event is the trigger.
 *
 * <p>The Tram consumer transport and dispatcher live in
 * {@link com.vab.notification.config.EventConsumerConfig}, not on this class, so
 * test slices such as {@code @DataJpaTest} don't inherit them.
 */
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
