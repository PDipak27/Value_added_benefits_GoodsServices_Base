package com.vab.billing.payment;

import com.vab.billing.payment.PaymentIntentRecord.Status;
import io.eventuate.common.json.mapper.JSonMapper;
import io.eventuate.tram.commands.consumer.CommandReplyToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access to {@code billing.payment_intent} and {@code billing.psp_webhook_event}. Joins the caller's
 * transaction (a Tram handler, the webhook or the sweeper), so a state change and the reply or ledger entry
 * it implies commit together.
 */
@Repository
public class PaymentIntentStore {

    private static final String T = "billing.payment_intent";
    private static final int MAX_ERROR = 300;

    private final JdbcTemplate jdbc;

    public PaymentIntentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false if the order already has a row (a concurrent cancel tombstone, or a redelivery). */
    public boolean insertCreated(String orderId, String intentId, String subscriberId, long amount, String currency,
                                 String checkoutUrl, Instant expiresAt) {
        return jdbc.update("""
                INSERT INTO %s (order_id, intent_id, subscriber_id, amount, currency, status, checkout_url, expires_at)
                VALUES (?, ?, ?, ?, ?, 'CREATED', ?, ?)
                ON CONFLICT (order_id) DO NOTHING""".formatted(T),
                orderId, intentId, subscriberId, amount, currency, checkoutUrl, ts(expiresAt)) == 1;
    }

    /** A cancel for an order whose intent doesn't exist yet: create-intent will see it and refuse. */
    public void insertCancelTombstone(String orderId, String reason) {
        jdbc.update("""
                INSERT INTO %s (order_id, status, last_error) VALUES (?, 'CANCELLED', ?)
                ON CONFLICT (order_id) DO NOTHING""".formatted(T), orderId, cap(reason));
    }

    public Optional<PaymentIntentRecord> lockByOrder(String orderId) {
        return jdbc.query("SELECT * FROM %s WHERE order_id = ? FOR UPDATE".formatted(T), ROW, orderId)
                .stream().findFirst();
    }

    public Optional<PaymentIntentRecord> lockByIntent(String intentId) {
        return jdbc.query("SELECT * FROM %s WHERE intent_id = ? FOR UPDATE".formatted(T), ROW, intentId)
                .stream().findFirst();
    }

    public void attachReplyToken(String orderId, CommandReplyToken token) {
        jdbc.update("UPDATE %s SET reply_token_json = ?, updated_at = now() WHERE order_id = ?".formatted(T),
                JSonMapper.toJson(token), orderId);
    }

    /** Moves to {@code status} and drops the reply token: whoever calls this has just sent the reply. */
    public void moveTo(String orderId, Status status, String error) {
        jdbc.update("""
                UPDATE %s SET status = ?, last_error = COALESCE(?, last_error), reply_token_json = NULL,
                              next_attempt_at = NULL, updated_at = now()
                 WHERE order_id = ?""".formatted(T), status.name(), cap(error), orderId);
    }

    /** VOID_OWED / REFUND_OWED: the sweeper retries the provider call from {@code nextAttemptAt}. */
    public void owe(String orderId, Status status, Instant nextAttemptAt, String error) {
        jdbc.update("""
                UPDATE %s SET status = ?, next_attempt_at = ?, last_error = ?, reply_token_json = NULL, updated_at = now()
                 WHERE order_id = ?""".formatted(T), status.name(), ts(nextAttemptAt), cap(error), orderId);
    }

    public void retryLater(String orderId, Instant nextAttemptAt, String error) {
        jdbc.update("UPDATE %s SET next_attempt_at = ?, last_error = ?, updated_at = now() WHERE order_id = ?"
                .formatted(T), ts(nextAttemptAt), cap(error), orderId);
    }

    /** Unpaid intents past their window; other sweepers skip the locked rows. */
    public List<PaymentIntentRecord> lockExpired(int limit) {
        return jdbc.query("""
                SELECT * FROM %s WHERE status = 'CREATED' AND expires_at <= now()
                 ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED""".formatted(T), ROW, limit);
    }

    public List<PaymentIntentRecord> lockOwedDue(int limit) {
        return jdbc.query("""
                SELECT * FROM %s WHERE status IN ('VOID_OWED', 'REFUND_OWED') AND next_attempt_at <= now()
                 ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED""".formatted(T), ROW, limit);
    }

    /** @return false if this provider event was already applied (webhooks are delivered at least once). */
    public boolean recordWebhookEvent(String eventId, String type) {
        return jdbc.update("INSERT INTO billing.psp_webhook_event (event_id, event_type) VALUES (?, ?) ON CONFLICT DO NOTHING",
                eventId, type) == 1;
    }

    private static final RowMapper<PaymentIntentRecord> ROW = (rs, i) -> new PaymentIntentRecord(
            rs.getString("order_id"),
            rs.getString("intent_id"),
            rs.getString("subscriber_id"),
            (Long) rs.getObject("amount", Long.class),
            rs.getString("currency"),
            Status.valueOf(rs.getString("status")),
            rs.getString("checkout_url"),
            instant(rs.getTimestamp("expires_at")),
            rs.getString("reply_token_json"),
            rs.getString("last_error"),
            instant(rs.getTimestamp("next_attempt_at")));

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String cap(String s) {
        return s == null || s.length() <= MAX_ERROR ? s : s.substring(0, MAX_ERROR);
    }
}
