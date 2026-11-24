package com.vab.order.it;

import com.vab.messaging.FailureClass;
import com.vab.messaging.MessagingHeaders;
import com.vab.messaging.ParkedMessage;
import com.vab.messaging.ParkedMessageStore;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.MessageImpl;
import io.eventuate.tram.messaging.producer.MessageProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DLQ re-drive against the real {@code eventuate} schema (Design/13 §7). */
class ParkedMessageRedriveIT extends AbstractOrderIntegrationTest {

    private static final String SUBSCRIBER = "billingCommandDispatcher";

    @Autowired ParkedMessageStore store;
    @Autowired MessageProducer producer;
    @Autowired JdbcTemplate jdbc;

    private String park(String messageId) {
        Map<String, String> headers = new HashMap<>();
        headers.put(Message.ID, messageId);
        headers.put(Message.DESTINATION, "billingService");
        headers.put("command_type", "external.Authorize");
        store.park(SUBSCRIBER, new MessageImpl("{\"amount\":500}", headers), FailureClass.POISON,
                new NullPointerException("bug"), 3);
        return messageId;
    }

    private long parkedId(String messageId) {
        return jdbc.queryForObject("SELECT id FROM eventuate.parked_message WHERE subscriber_id = ? AND message_id = ?",
                Long.class, SUBSCRIBER, messageId);
    }

    private ParkedMessage.Status status(long id) {
        return store.find(id).orElseThrow().status();
    }

    @Test
    void redrive_sends_a_copy_addressed_to_the_parking_subscriber() {
        long id = parkedId(park(UUID.randomUUID().toString()));

        Optional<String> newId = store.redrive(id, producer);

        assertThat(newId).isPresent();
        Map<String, Object> outbox = jdbc.queryForMap("SELECT destination, headers FROM eventuate.message WHERE id = ?",
                newId.get());
        assertThat(outbox.get("destination")).isEqualTo("billingService");
        assertThat((String) outbox.get("headers")).contains(MessagingHeaders.REDRIVE_TARGET, SUBSCRIBER);
        assertThat(status(id)).isEqualTo(ParkedMessage.Status.REDRIVEN);
    }

    @Test
    void original_processed_after_parking_is_not_sent_again() {
        String messageId = park(UUID.randomUUID().toString());
        long id = parkedId(messageId);
        jdbc.update("INSERT INTO eventuate.received_messages (consumer_id, message_id, creation_time) VALUES (?, ?, ?)",
                SUBSCRIBER, messageId, System.currentTimeMillis());

        long outboxBefore = outboxCount();

        assertThat(store.redrive(id, producer)).isEmpty();
        assertThat(status(id)).isEqualTo(ParkedMessage.Status.PROCESSED);
        assertThat(outboxCount()).isEqualTo(outboxBefore);
    }

    @Test
    void message_without_destination_is_unroutable_and_can_only_be_discarded() {
        String messageId = UUID.randomUUID().toString();
        store.park(SUBSCRIBER, new MessageImpl("{}", new HashMap<>(Map.of(Message.ID, messageId))),
                FailureClass.POISON, new IllegalArgumentException("Message has no DESTINATION header"), 0);
        long id = parkedId(messageId);
        assertThat(status(id)).isEqualTo(ParkedMessage.Status.UNROUTABLE);
        long outboxBefore = outboxCount();

        assertThatThrownBy(() -> store.redrive(id, producer))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("only discarded");
        assertThat(outboxCount()).isEqualTo(outboxBefore);
        assertThat(status(id)).isEqualTo(ParkedMessage.Status.UNROUTABLE);

        store.discard(id);
        assertThat(status(id)).isEqualTo(ParkedMessage.Status.DISCARDED);
    }

    private long outboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM eventuate.message", Long.class);
    }
}
