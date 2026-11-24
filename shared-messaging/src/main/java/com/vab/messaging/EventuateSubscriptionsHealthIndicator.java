package com.vab.messaging;

import io.eventuate.messaging.kafka.basic.consumer.EventuateKafkaConsumer;
import io.eventuate.messaging.kafka.basic.consumer.EventuateKafkaConsumerState;
import io.eventuate.messaging.kafka.consumer.MessageConsumerKafkaImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports each Eventuate Kafka subscription's state (Design/13 §9). DOWN once any subscription has died
 * ({@code MESSAGE_HANDLING_FAILED}, {@code FAILED}, {@code FAILED_TO_START}); Eventuate itself never restarts
 * it, so a restart is the fix. Configured into the {@code liveness} health group, so k8s restarts the pod.
 *
 * <p>Eventuate keeps its consumers in a private list, so this reads it by reflection. If that breaks (an
 * Eventuate upgrade), the indicator reports UNKNOWN, never DOWN: a reflection problem must not restart pods.
 */
public class EventuateSubscriptionsHealthIndicator implements HealthIndicator {

    private static final Set<EventuateKafkaConsumerState> DEAD = EnumSet.of(
            EventuateKafkaConsumerState.MESSAGE_HANDLING_FAILED,
            EventuateKafkaConsumerState.FAILED,
            EventuateKafkaConsumerState.FAILED_TO_START);

    private final ObjectProvider<MessageConsumerKafkaImpl> messageConsumer;

    public EventuateSubscriptionsHealthIndicator(ObjectProvider<MessageConsumerKafkaImpl> messageConsumer) {
        this.messageConsumer = messageConsumer;
    }

    @Override
    public Health health() {
        MessageConsumerKafkaImpl kafka = messageConsumer.getIfAvailable();
        if (kafka == null) {
            return Health.unknown().withDetail("error", "No Eventuate Kafka message consumer in this application").build();
        }
        List<EventuateKafkaConsumer> consumers;
        try {
            consumers = consumers(kafka);
        } catch (RuntimeException | ReflectiveOperationException e) {
            return Health.unknown().withDetail("error", "Cannot read Eventuate consumers: " + e).build();
        }
        Map<String, String> states = new LinkedHashMap<>();
        boolean dead = false;
        for (EventuateKafkaConsumer consumer : consumers) {
            EventuateKafkaConsumerState state = consumer.getState();
            states.put(subscriberId(consumer), String.valueOf(state));
            dead |= DEAD.contains(state);
        }
        return (dead ? Health.down() : Health.up()).withDetail("subscriptions", states).build();
    }

    @SuppressWarnings("unchecked")
    private static List<EventuateKafkaConsumer> consumers(MessageConsumerKafkaImpl kafka) throws ReflectiveOperationException {
        Field field = MessageConsumerKafkaImpl.class.getDeclaredField("consumers");
        field.setAccessible(true);
        List<EventuateKafkaConsumer> live = (List<EventuateKafkaConsumer>) field.get(kafka);
        // A plain ArrayList that Eventuate appends to while subscribing at startup: a concurrent append can
        // make this copy throw ConcurrentModificationException → UNKNOWN for that one probe.
        return new ArrayList<>(live);
    }

    private static String subscriberId(EventuateKafkaConsumer consumer) {
        try {
            Field field = EventuateKafkaConsumer.class.getDeclaredField("subscriberId");
            field.setAccessible(true);
            return String.valueOf(field.get(consumer));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "subscriber@" + Integer.toHexString(System.identityHashCode(consumer));
        }
    }
}
