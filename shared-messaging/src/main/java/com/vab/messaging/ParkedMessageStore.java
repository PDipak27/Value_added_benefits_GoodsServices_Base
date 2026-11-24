package com.vab.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.MessageImpl;
import io.eventuate.tram.messaging.producer.MessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * JDBC access to the DLQ table {@code <schema>.parked_message} (Design/13 §7).
 *
 * <p>{@link #park} always runs in its <b>own</b> transaction ({@code REQUIRES_NEW}): it's called by the
 * decorator after the message's own Tram transaction has rolled back. {@link #redrive} and {@link #discard}
 * join the caller's transaction if there is one, so a re-drive's outbox insert and the status change
 * commit together (the Tram producer writes through the same connection).
 */
public class ParkedMessageStore {

    private static final Logger log = LoggerFactory.getLogger(ParkedMessageStore.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int MAX_ERROR_CHARS = 8_000;
    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate newTx;
    private final TransactionTemplate joinTx;
    private final ObjectMapper json = new ObjectMapper();
    private final String table;
    private final String receivedTable;

    public ParkedMessageStore(JdbcTemplate jdbc, PlatformTransactionManager txManager, String schema) {
        if (!IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name: " + schema);
        }
        this.jdbc   = jdbc;
        this.table  = schema + ".parked_message";
        this.receivedTable = schema + ".received_messages";
        this.newTx  = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.joinTx = new TransactionTemplate(txManager);
    }

    /**
     * Stores the message as received. Idempotent: the same message parked twice by the same subscriber
     * (redelivery after a crash between park and offset commit) keeps the first row. A message without a
     * {@code DESTINATION} header is stored as {@code UNROUTABLE}.
     */
    public void park(String subscriberId, Message message, FailureClass failureClass, Throwable error, int attempts) {
        Map<String, String> headers = message.getHeaders() == null ? Map.of() : message.getHeaders();
        String messageId = Optional.ofNullable(headers.get(Message.ID)).orElse("no-id-" + UUID.randomUUID());
        String destination = headers.get(Message.DESTINATION);
        ParkedMessage.Status initial = destination == null ? ParkedMessage.Status.UNROUTABLE : ParkedMessage.Status.PARKED;
        String headersJson = toJson(headers);
        String errorText   = stackTraceOf(error);
        newTx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO %s (subscriber_id, channel, message_id, headers_json, payload, failure_class, error, attempts, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (subscriber_id, message_id) DO NOTHING""".formatted(table),
                subscriberId, destination == null ? "unknown" : destination, messageId, headersJson, message.getPayload(),
                failureClass.name(), errorText, attempts, initial.name()));
    }

    /** True if the table exists. Used once at startup to warn early, not on the hot path. */
    public boolean tableExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?::text) IS NOT NULL", Boolean.class, table));
    }

    public List<ParkedMessage> list(ParkedMessage.Status status, int limit) {
        return jdbc.query("SELECT * FROM %s WHERE status = ? ORDER BY id DESC LIMIT ?".formatted(table),
                rowMapper(), status.name(), Math.max(1, Math.min(limit, 500)));
    }

    public long count(ParkedMessage.Status status) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM %s WHERE status = ?".formatted(table), Long.class, status.name());
        return n == null ? 0 : n;
    }

    public Optional<ParkedMessage> find(long id) {
        return jdbc.query("SELECT * FROM %s WHERE id = ?".formatted(table), rowMapper(), id).stream().findFirst();
    }

    /**
     * Re-publishes a parked message to its original channel, addressed to the subscriber that parked it
     * ({@link MessagingHeaders#REDRIVE_TARGET}; every other subscriber of the channel skips it). Outbox insert
     * and status change commit together. If the subscriber has since processed the original, nothing is sent
     * and the row becomes {@code PROCESSED}.
     *
     * @return the new message id, or empty if the original was already processed
     * @throws IllegalStateException if the row doesn't exist or isn't {@code PARKED} (an {@code UNROUTABLE} row
     *         can only be discarded)
     */
    public Optional<String> redrive(long id, MessageProducer producer) {
        return joinTx.execute(status -> {
            ParkedMessage row = lockOpen(id);
            if (row.status() == ParkedMessage.Status.UNROUTABLE) {
                throw new IllegalStateException("Parked message " + id + " is UNROUTABLE (no DESTINATION header): "
                        + "it cannot be re-driven, only discarded");
            }
            // Kafka redelivers a parked message if the service dies before the offset commit, and it may succeed
            // then. The copy gets a new id, so Tram's dedupe wouldn't stop it running twice.
            if (processedByOriginalSubscriber(row)) {
                jdbc.update("UPDATE %s SET status = 'PROCESSED', resolved_at = now() WHERE id = ?".formatted(table), id);
                log.warn("Parked message was processed after parking, not re-driven: parkedId={}, subscriber={}, messageId={}",
                        id, row.subscriberId(), row.messageId());
                return Optional.empty();
            }
            Map<String, String> headers = new HashMap<>(row.headers());
            headers.remove(Message.ID);            // a new id is assigned on insert
            headers.remove(Message.DATE);
            headers.remove(Message.DESTINATION);   // set again by the producer
            headers.put(MessagingHeaders.REDRIVE_TARGET, row.subscriberId());
            headers.put(MessagingHeaders.REDRIVEN_FROM, Long.toString(row.id()));
            Message message = new MessageImpl(row.payload(), headers);
            producer.send(row.channel(), message);
            String newId = message.getId();
            jdbc.update("UPDATE %s SET status = 'REDRIVEN', resolved_at = now(), redrive_message_id = ? WHERE id = ?"
                    .formatted(table), newId, id);
            log.info("Re-drove parked message: parkedId={}, subscriber={}, channel={}, newMessageId={}",
                    id, row.subscriberId(), row.channel(), newId);
            return Optional.of(newId);
        });
    }

    private boolean processedByOriginalSubscriber(ParkedMessage row) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM %s WHERE consumer_id = ? AND message_id = ?"
                .formatted(receivedTable), Integer.class, row.subscriberId(), row.messageId());
        return n != null && n > 0;
    }

    /** @throws IllegalStateException if the row doesn't exist or isn't {@code PARKED} / {@code UNROUTABLE} */
    public void discard(long id) {
        joinTx.executeWithoutResult(status -> {
            lockOpen(id);
            jdbc.update("UPDATE %s SET status = 'DISCARDED', resolved_at = now() WHERE id = ?".formatted(table), id);
            log.info("Discarded parked message: parkedId={}", id);
        });
    }

    /** Locks a row that still awaits an admin decision: {@code PARKED} or {@code UNROUTABLE}. */
    private ParkedMessage lockOpen(long id) {
        ParkedMessage row = jdbc.query("SELECT * FROM %s WHERE id = ? FOR UPDATE".formatted(table), rowMapper(), id)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Parked message not found: " + id));
        if (row.status() != ParkedMessage.Status.PARKED && row.status() != ParkedMessage.Status.UNROUTABLE) {
            throw new IllegalStateException("Parked message " + id + " is " + row.status() + ", not PARKED");
        }
        return row;
    }

    private RowMapper<ParkedMessage> rowMapper() {
        return (rs, i) -> new ParkedMessage(
                rs.getLong("id"),
                rs.getString("subscriber_id"),
                rs.getString("channel"),
                rs.getString("message_id"),
                fromJson(rs.getString("headers_json")),
                rs.getString("payload"),
                FailureClass.valueOf(rs.getString("failure_class")),
                rs.getString("error"),
                rs.getInt("attempts"),
                ParkedMessage.Status.valueOf(rs.getString("status")),
                toInstant(rs.getTimestamp("parked_at")),
                toInstant(rs.getTimestamp("resolved_at")),
                rs.getString("redrive_message_id"));
    }

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private String toJson(Map<String, String> headers) {
        try {
            return json.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise message headers", e);
        }
    }

    private Map<String, String> fromJson(String s) {
        try {
            return s == null ? Map.of() : json.readValue(s, HEADERS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse stored message headers", e);
        }
    }

    private static String stackTraceOf(Throwable t) {
        if (t == null) return null;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() <= MAX_ERROR_CHARS ? s : s.substring(0, MAX_ERROR_CHARS) + "\n…(truncated)";
    }
}
