package com.vab.messaging;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.SocketException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Classifies a message-handling failure by walking its whole cause chain (Eventuate wraps handler
 * exceptions, e.g. {@code UnsupportedOperationException("A command handler … threw an exception", cause)}).
 *
 * <p>Precedence: {@link FailureClass#INFRA_DOWN} &gt; {@link FailureClass#CONTENTION} &gt;
 * {@link FailureClass#POISON}. If any link says "the database is unreachable", retrying soon is pointless
 * and parking would fail anyway, so waiting wins. Unknown failures are POISON: parked after a few attempts,
 * never retried forever.
 *
 * <p>A pool that can't hand out a connection (Hikari timeout) counts as INFRA_DOWN, whether the DB is
 * down or the pool is saturated: in both cases the message must wait, not go to the DLQ.
 */
public final class FailureClassifier {

    /** Postgres SQLState classes / codes for "server unreachable or going away". */
    private static final Set<String> INFRA_SQLSTATE_CODES = Set.of(
            "57P01",   // admin_shutdown
            "57P02",   // crash_shutdown
            "57P03");  // cannot_connect_now (starting up / recovering)
    private static final Set<String> INFRA_SQLSTATE_CLASSES = Set.of(
            "08",      // connection exception
            "53");     // insufficient resources (too_many_connections, disk_full, out_of_memory)

    /** Postgres SQLState codes for "try again". */
    private static final Set<String> CONTENTION_SQLSTATE_CODES = Set.of(
            "40001",   // serialization_failure
            "40P01",   // deadlock_detected
            "55P03",   // lock_not_available (lock_timeout)
            "57014");  // query_canceled (statement_timeout)

    /** Matched by name so this module needs no Mongo / Hibernate / JPA dependency. */
    private static final Set<String> INFRA_CLASS_NAMES = Set.of(
            "com.mongodb.MongoSocketException",
            "com.mongodb.MongoTimeoutException",
            "com.mongodb.MongoNotPrimaryException",
            "com.mongodb.MongoNodeIsRecoveringException",
            "org.hibernate.exception.JDBCConnectionException");
    private static final Set<String> CONTENTION_CLASS_NAMES = Set.of(
            "jakarta.persistence.OptimisticLockException",
            "jakarta.persistence.PessimisticLockException",
            "jakarta.persistence.LockTimeoutException",
            "org.hibernate.StaleStateException",
            "org.hibernate.exception.LockAcquisitionException");

    /** Unique constraint of Eventuate's outbox table: an id clash between co-located JVMs (handoff Fact 2). */
    private static final String OUTBOX_PK = "message_pkey";

    /**
     * Eventuate's tracing interceptor ({@code ObservationMessageProducerInterceptor.postSend}) throws this
     * <em>instead of</em> the real send error, because a failed outbox insert leaves the message without an
     * ID. The real cause (usually the outbox-id clash) is lost, so treat it as retryable.
     */
    private static final String MASKED_SEND_FAILURE = "No such header: ID";

    private FailureClassifier() {}

    public static FailureClass classify(Throwable failure) {
        boolean contention = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = failure; c != null && seen.add(c); c = c.getCause()) {
            if (isInfra(c)) return FailureClass.INFRA_DOWN;
            if (!contention && isContention(c)) contention = true;
        }
        return contention ? FailureClass.CONTENTION : FailureClass.POISON;
    }

    private static boolean isInfra(Throwable c) {
        if (c instanceof CannotGetJdbcConnectionException
                || c instanceof CannotCreateTransactionException
                || c instanceof DataAccessResourceFailureException
                || c instanceof TransientDataAccessResourceException
                || c instanceof SQLTransientConnectionException
                || c instanceof SQLNonTransientConnectionException
                || c instanceof SocketException            // incl. ConnectException, "Connection reset"
                || c instanceof UnknownHostException) {    // DB host not resolvable while it restarts (k8s)
            return true;
        }
        if (c instanceof SQLException sql && sql.getSQLState() != null) {
            String state = sql.getSQLState();
            if (INFRA_SQLSTATE_CODES.contains(state)) return true;
            if (state.length() >= 2 && INFRA_SQLSTATE_CLASSES.contains(state.substring(0, 2))) return true;
        }
        return isInstanceOfAny(c, INFRA_CLASS_NAMES);
    }

    private static boolean isContention(Throwable c) {
        if (c instanceof ConcurrencyFailureException) return true;   // optimistic + pessimistic lock failures
        if (c instanceof DuplicateKeyException && mentions(c, OUTBOX_PK)) return true;
        if (c instanceof SQLException sql && sql.getSQLState() != null) {
            String state = sql.getSQLState();
            if (CONTENTION_SQLSTATE_CODES.contains(state)) return true;
            if ("23505".equals(state) && mentions(c, OUTBOX_PK)) return true;   // unique_violation on the outbox PK
        }
        if (c instanceof TransientDataAccessException) return true;  // e.g. QueryTimeoutException
        if (c.getClass() == RuntimeException.class && c.getMessage() != null
                && c.getMessage().startsWith(MASKED_SEND_FAILURE)) {
            return true;
        }
        return isInstanceOfAny(c, CONTENTION_CLASS_NAMES);
    }

    private static boolean mentions(Throwable t, String text) {
        return t.getMessage() != null && t.getMessage().contains(text);
    }

    private static boolean isInstanceOfAny(Throwable t, Set<String> classNames) {
        for (Class<?> k = t.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            if (classNames.contains(k.getName())) return true;
        }
        return false;
    }
}
