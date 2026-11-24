package com.vab.inventory.it;

import com.vab.events.inventory.ReserveInventoryCommand;
import com.vab.messaging.EventuateSubscriptionsHealthIndicator;
import io.eventuate.common.json.mapper.JSonMapper;
import io.eventuate.tram.commands.common.CommandMessageHeaders;
import io.eventuate.tram.commands.common.CommandNameMapping;
import io.eventuate.tram.commands.producer.CommandMessageFactory;
import io.eventuate.tram.messaging.common.Message;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resilient decorator inside the real Tram consumer (Design/13 §4/§7): a poison command on
 * {@code inventoryService} is parked after its retries, the next command on the same partition is still
 * processed and replied to, and the subscription stays alive. Messages go straight to Kafka (no CDC in ITs).
 */
@SpringBootTest
class MessageFailureHandlingIT extends AbstractIntegrationTest {

    private static final String CHANNEL = "inventoryService";
    private static final String SUBSCRIBER = "inventoryCommandDispatcher";

    @Autowired CommandNameMapping commandNameMapping;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventuateSubscriptionsHealthIndicator subscriptions;

    private Message command(String commandType, String replyTo) {
        Message m = CommandMessageFactory.makeMessage(commandNameMapping, CHANNEL,
                new ReserveInventoryCommand("IT_NO_SUCH_OFFER_" + UUID.randomUUID(), 1), replyTo, Map.of());
        if (commandType != null) m.setHeader(CommandMessageHeaders.COMMAND_TYPE, commandType);
        m.setHeader(Message.ID, UUID.randomUUID().toString());
        m.setHeader(Message.DESTINATION, CHANNEL);   // the outbox producer sets this; Tram's tracing requires it
        return m;
    }

    private static void send(String key, Message... messages) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(config)) {
            for (Message m : messages) {
                producer.send(new ProducerRecord<>(CHANNEL, key, JSonMapper.toJson(m).getBytes(StandardCharsets.UTF_8)));
            }
            producer.flush();
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("Timed out waiting for: " + what);
            Thread.sleep(250);
        }
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void poison_is_parked_and_the_next_message_on_the_partition_is_still_processed() throws Exception {
        String replyTo = "inventoryIT-replies-" + UUID.randomUUID();
        Message poison = command("com.vab.events.inventory.NoSuchCommand", replyTo);
        Message good = command(null, replyTo);

        send(UUID.randomUUID().toString(), poison, good);

        await("reply to the good command", () ->
                count("SELECT count(*) FROM eventuate.message WHERE destination = ?", replyTo) == 1);

        Map<String, Object> parked = jdbc.queryForMap(
                "SELECT failure_class, attempts, status FROM eventuate.parked_message WHERE subscriber_id = ? AND message_id = ?",
                SUBSCRIBER, poison.getId());
        assertThat(parked).containsEntry("failure_class", "POISON").containsEntry("attempts", 3)
                .containsEntry("status", "PARKED");
        assertThat(count("SELECT count(*) FROM eventuate.received_messages WHERE consumer_id = ? AND message_id = ?",
                SUBSCRIBER, poison.getId())).as("the poison's own transaction rolled back").isZero();
        assertThat(count("SELECT count(*) FROM eventuate.received_messages WHERE consumer_id = ? AND message_id = ?",
                SUBSCRIBER, good.getId())).isEqualTo(1);
        assertThat(subscriptions.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void message_without_destination_is_parked_instead_of_killing_the_subscription() throws Exception {
        String replyTo = "inventoryIT-replies-" + UUID.randomUUID();
        Message malformed = command(null, replyTo);
        malformed.removeHeader(Message.DESTINATION);
        Message good = command(null, replyTo);

        send(UUID.randomUUID().toString(), malformed, good);

        await("reply to the good command", () ->
                count("SELECT count(*) FROM eventuate.message WHERE destination = ?", replyTo) == 1);
        assertThat(jdbc.queryForObject("SELECT status FROM eventuate.parked_message WHERE subscriber_id = ? AND message_id = ?",
                String.class, SUBSCRIBER, malformed.getId())).isEqualTo("UNROUTABLE");
        assertThat(subscriptions.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void subscriptions_health_lists_the_dispatcher_and_stays_up() {
        Health health = subscriptions.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        Map<String, String> states = (Map<String, String>) health.getDetails().get("subscriptions");
        assertThat(states).containsEntry(SUBSCRIBER, "STARTED");
    }
}
