package com.vab.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static com.vab.messaging.FailureClass.*;
import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    private static FailureClass classify(Throwable t) {
        return FailureClassifier.classify(t);
    }

    @Test
    void lock_conflicts_are_contention() {
        assertThat(classify(new OptimisticLockingFailureException("Order ord-1 was updated concurrently"))).isEqualTo(CONTENTION);
        assertThat(classify(new CannotAcquireLockException("lock"))).isEqualTo(CONTENTION);
        assertThat(classify(new QueryTimeoutException("statement timeout"))).isEqualTo(CONTENTION);
    }

    @Test
    void postgres_retryable_sqlstates_are_contention() {
        assertThat(classify(new SQLException("deadlock", "40P01"))).isEqualTo(CONTENTION);
        assertThat(classify(new SQLException("serialization", "40001"))).isEqualTo(CONTENTION);
        assertThat(classify(new SQLException("lock timeout", "55P03"))).isEqualTo(CONTENTION);
    }

    @Test
    void outbox_id_clash_is_contention_but_other_duplicates_are_poison() {
        assertThat(classify(new DuplicateKeyException("duplicate key value violates unique constraint \"message_pkey\"")))
                .isEqualTo(CONTENTION);
        assertThat(classify(new DuplicateKeyException("duplicate key value violates unique constraint \"orders_pkey\"")))
                .isEqualTo(POISON);
    }

    @Test
    void masked_send_failure_from_the_tracing_interceptor_is_contention() {
        assertThat(classify(new RuntimeException("No such header: ID in this message"))).isEqualTo(CONTENTION);
    }

    @Test
    void unreachable_database_is_infra_down() {
        assertThat(classify(new CannotGetJdbcConnectionException("no connection",
                new SQLException("refused", "08001", new ConnectException("Connection refused"))))).isEqualTo(INFRA_DOWN);
        assertThat(classify(new CannotCreateTransactionException("could not open"))).isEqualTo(INFRA_DOWN);
        assertThat(classify(new SQLException("admin shutdown", "57P01"))).isEqualTo(INFRA_DOWN);
        assertThat(classify(new SQLException("too many clients", "53300"))).isEqualTo(INFRA_DOWN);
    }

    @Test
    void exhausted_pool_waits_instead_of_parking() {
        assertThat(classify(new CannotGetJdbcConnectionException("timeout",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")))).isEqualTo(INFRA_DOWN);
    }

    @Test
    void infra_down_anywhere_in_the_chain_wins_over_contention() {
        Throwable t = new CannotAcquireLockException("lock", new SQLException("gone", "08006"));
        assertThat(classify(t)).isEqualTo(INFRA_DOWN);
    }

    @Test
    void cause_chain_is_walked_through_eventuate_wrappers() {
        Throwable wrapped = new UnsupportedOperationException("A command handler threw an exception",
                new OptimisticLockingFailureException("Order ord-1 was updated concurrently"));
        assertThat(classify(wrapped)).isEqualTo(CONTENTION);
    }

    @Test
    void everything_else_is_poison() {
        assertThat(classify(new NullPointerException())).isEqualTo(POISON);
        assertThat(classify(new IllegalStateException("No method for message"))).isEqualTo(POISON);
        assertThat(classify(new DataIntegrityViolationException("check constraint"))).isEqualTo(POISON);
    }

    @Test
    void self_referencing_cause_does_not_loop() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(classify(b)).isEqualTo(POISON);
    }
}
