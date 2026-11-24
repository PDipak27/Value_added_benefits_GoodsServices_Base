package com.vab.fulfilment.provisioning;

import com.vab.events.fulfilment.FulfilOrderCommand;
import io.eventuate.common.json.mapper.JSonMapper;
import io.eventuate.tram.commands.consumer.CommandReplyToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SQL for {@code fulfilment.provisioning_task}. No transaction handling here: every method runs inside the
 * caller's transaction (the Tram message tx of a handler, or a {@code @Transactional} method of
 * {@link ProvisioningTaskService}), so the row change commits together with the saga reply sent there.
 */
@Repository
public class ProvisioningTaskStore {

    private static final String T = "fulfilment.provisioning_task";
    private static final int MAX_DETAIL = 300;

    private final JdbcTemplate jdbc;

    public ProvisioningTaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── create ──────────────────────────────────────────────────────────────

    /** First attempt said UNAVAILABLE. @return false if a row for the order already exists. */
    public boolean insertRetrying(FulfilOrderCommand cmd, CommandReplyToken token, String reason, String detail,
                                  boolean uncertain, Instant nextAttemptAt, Instant deadlineAt) {
        return jdbc.update("""
                INSERT INTO %s (order_id, command_json, reply_token_json, status, ott_uncertain,
                                last_reason, last_detail, attempts, next_attempt_at, deadline_at)
                VALUES (?, ?, ?, 'RETRYING', ?, ?, ?, 1, ?, ?)
                ON CONFLICT (order_id) DO NOTHING""".formatted(T),
                cmd.getOrderId(), JSonMapper.toJson(cmd), JSonMapper.toJson(token), uncertain,
                reason, cap(detail), ts(nextAttemptAt), ts(deadlineAt)) == 1;
    }

    /** OTT accepted on the first attempt: hold the reply until its activation webhook. */
    public void insertAwaiting(FulfilOrderCommand cmd, CommandReplyToken token, String externalRef, Instant deadlineAt) {
        jdbc.update("""
                INSERT INTO %s (order_id, command_json, reply_token_json, status, ott_uncertain, external_ref,
                                attempts, deadline_at)
                VALUES (?, ?, ?, 'AWAITING_ACTIVATION', TRUE, ?, 1, ?)""".formatted(T),
                cmd.getOrderId(), JSonMapper.toJson(cmd), JSonMapper.toJson(token), externalRef, ts(deadlineAt));
    }

    /** A cancel that arrived before its fulfil command. */
    public void insertCancelTombstone(String orderId, String reason, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO %s (order_id, status, cancel_requested, cancel_reason, attempts, deadline_at)
                VALUES (?, 'CANCEL_TOMBSTONE', TRUE, ?, 0, ?)
                ON CONFLICT (order_id) DO NOTHING""".formatted(T), orderId, cap(reason), ts(expiresAt));
    }

    /** OTT may hold an entitlement for an order that is being refunded: revoke it from {@code notBefore}. */
    public void upsertRevokeOwed(String orderId, Instant notBefore) {
        jdbc.update("""
                INSERT INTO %s (order_id, status, ott_uncertain, attempts, next_attempt_at, deadline_at)
                VALUES (?, 'REVOKE_OWED', TRUE, 0, ?, ?)
                ON CONFLICT (order_id) DO UPDATE
                   SET status = 'REVOKE_OWED', ott_uncertain = TRUE, next_attempt_at = EXCLUDED.next_attempt_at,
                       lease_owner = NULL, lease_until = NULL, command_json = NULL, reply_token_json = NULL,
                       updated_at = now()""".formatted(T), orderId, ts(notBefore), ts(notBefore));
    }

    // ── read / lock ─────────────────────────────────────────────────────────

    public Optional<ProvisioningTask> lock(String orderId) {
        return jdbc.query("SELECT * FROM %s WHERE order_id = ? FOR UPDATE".formatted(T), ROW, orderId)
                .stream().findFirst();
    }

    /** Deletes a tombstone for the order. @return true if one existed. */
    public boolean consumeTombstone(String orderId) {
        return jdbc.update("DELETE FROM %s WHERE order_id = ? AND status = 'CANCEL_TOMBSTONE'".formatted(T), orderId) == 1;
    }

    public Optional<ProvisioningTask> lockIfOwned(String orderId, String owner) {
        return jdbc.query("""
                SELECT * FROM %s WHERE order_id = ? AND status = 'IN_PROGRESS' AND lease_owner = ?
                FOR UPDATE""".formatted(T), ROW, orderId, owner).stream().findFirst();
    }

    /** Open rows that must be answered now without calling OTT: cancelled, or past the deadline. */
    public List<ProvisioningTask> lockDecisionsDue(int limit) {
        return jdbc.query("""
                SELECT * FROM %s
                 WHERE status IN ('RETRYING','AWAITING_ACTIVATION') AND (cancel_requested OR deadline_at <= now())
                 ORDER BY deadline_at
                 LIMIT ? FOR UPDATE SKIP LOCKED""".formatted(T), ROW, limit);
    }

    /** RETRYING rows due for an attempt → IN_PROGRESS under a lease for {@code owner}. */
    public List<ProvisioningTask> claimDue(int limit, String owner, Duration lease) {
        return jdbc.query("""
                UPDATE %1$s t
                   SET status = 'IN_PROGRESS', lease_owner = ?, lease_until = now() + (? * interval '1 second'),
                       attempts = attempts + 1, updated_at = now()
                 WHERE t.order_id IN (SELECT order_id FROM %1$s
                                       WHERE status = 'RETRYING' AND NOT cancel_requested
                                         AND deadline_at > now() AND next_attempt_at <= now()
                                       ORDER BY next_attempt_at
                                       LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING t.*""".formatted(T), ROW, owner, lease.toSeconds(), limit);
    }

    /** REVOKE_OWED rows due for a revoke attempt; pushes their next attempt out by {@code lease} while we try. */
    public List<String> claimRevokesDue(int limit, Duration lease) {
        return jdbc.queryForList("""
                UPDATE %1$s t SET next_attempt_at = now() + (? * interval '1 second'), updated_at = now()
                 WHERE t.order_id IN (SELECT order_id FROM %1$s
                                       WHERE status = 'REVOKE_OWED' AND next_attempt_at <= now()
                                       ORDER BY next_attempt_at
                                       LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING t.order_id""".formatted(T), String.class, lease.toSeconds(), limit);
    }

    // ── update / delete ─────────────────────────────────────────────────────

    /** Sets the cancel flag on an open row (RETRYING / IN_PROGRESS / AWAITING_ACTIVATION). @return true if it was set. */
    public boolean requestCancel(String orderId, String reason) {
        return jdbc.update("""
                UPDATE %s SET cancel_requested = TRUE, cancel_reason = ?, updated_at = now()
                 WHERE order_id = ? AND status IN ('RETRYING','IN_PROGRESS','AWAITING_ACTIVATION')""".formatted(T),
                cap(reason), orderId) == 1;
    }

    /** A poller's attempt was accepted (202): wait for OTT's webhook. OTT now holds an entitlement → uncertain. */
    public void toAwaiting(String orderId, String externalRef) {
        jdbc.update("""
                UPDATE %s SET status = 'AWAITING_ACTIVATION', external_ref = ?, ott_uncertain = TRUE,
                              lease_owner = NULL, lease_until = NULL, next_attempt_at = NULL, updated_at = now()
                 WHERE order_id = ?""".formatted(T), externalRef, orderId);
    }

    /** @return false if this OTT event was already applied (webhooks are delivered at least once). */
    public boolean recordWebhookEvent(String eventId, String type) {
        return jdbc.update("INSERT INTO fulfilment.ott_webhook_event (event_id, event_type) VALUES (?, ?) ON CONFLICT DO NOTHING",
                eventId, type) == 1;
    }

    public void backToRetrying(String orderId, Instant nextAttemptAt, String reason, String detail, boolean uncertain) {
        jdbc.update("""
                UPDATE %s SET status = 'RETRYING', lease_owner = NULL, lease_until = NULL, next_attempt_at = ?,
                              last_reason = ?, last_detail = ?, ott_uncertain = ott_uncertain OR ?, updated_at = now()
                 WHERE order_id = ?""".formatted(T), ts(nextAttemptAt), reason, cap(detail), uncertain, orderId);
    }

    public void delete(String orderId) {
        jdbc.update("DELETE FROM %s WHERE order_id = ?".formatted(T), orderId);
    }

    public void deleteRevokeOwed(String orderId) {
        jdbc.update("DELETE FROM %s WHERE order_id = ? AND status = 'REVOKE_OWED'".formatted(T), orderId);
    }

    /** IN_PROGRESS rows whose poller died → RETRYING now. The lost attempt's fate is unknown → uncertain. */
    public int reclaimExpiredLeases() {
        return jdbc.update("""
                UPDATE %s SET status = 'RETRYING', lease_owner = NULL, lease_until = NULL, next_attempt_at = now(),
                              ott_uncertain = TRUE, updated_at = now()
                 WHERE status = 'IN_PROGRESS' AND lease_until < now()""".formatted(T));
    }

    public int deleteExpiredTombstones() {
        return jdbc.update("DELETE FROM %s WHERE status = 'CANCEL_TOMBSTONE' AND deadline_at < now()".formatted(T));
    }

    public long count(ProvisioningTask.Status status) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM %s WHERE status = ?".formatted(T), Long.class, status.name());
        return n == null ? 0 : n;
    }

    /** Age in seconds of the oldest row in {@code status}, 0 if none. */
    public double oldestAgeSeconds(ProvisioningTask.Status status) {
        Double s = jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) FROM %s WHERE status = ?""".formatted(T),
                Double.class, status.name());
        return s == null ? 0 : s;
    }

    // ── mapping ─────────────────────────────────────────────────────────────

    private static final RowMapper<ProvisioningTask> ROW = (rs, i) -> new ProvisioningTask(
            rs.getString("order_id"),
            rs.getString("command_json"),
            rs.getString("reply_token_json"),
            ProvisioningTask.Status.valueOf(rs.getString("status")),
            rs.getBoolean("cancel_requested"),
            rs.getString("cancel_reason"),
            rs.getBoolean("ott_uncertain"),
            rs.getInt("attempts"),
            instant(rs.getTimestamp("next_attempt_at")),
            instant(rs.getTimestamp("deadline_at")),
            rs.getString("lease_owner"),
            rs.getString("external_ref"));

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String cap(String s) {
        return s == null || s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL - 3) + "...";
    }
}
