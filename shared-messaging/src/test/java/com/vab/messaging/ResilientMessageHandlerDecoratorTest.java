package com.vab.messaging;

import io.eventuate.tram.consumer.common.MessageHandlerDecoratorChain;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.MessageImpl;
import io.eventuate.tram.messaging.common.SubscriberIdAndMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ResilientMessageHandlerDecoratorTest {

    private static final String SUBSCRIBER = "billingCommandDispatcher";

    private final ParkedMessageStore parking = mock(ParkedMessageStore.class);
    private final MessagingMetrics metrics = mock(MessagingMetrics.class);
    private final MessagingResilienceProperties props = new MessagingResilienceProperties();
    private final List<Long> sleeps = new ArrayList<>();
    private boolean shuttingDown;

    private ResilientMessageHandlerDecorator decorator;
    private Message message;

    @BeforeEach
    void setUp() {
        decorator = new ResilientMessageHandlerDecorator(parking, props, metrics, millis -> {
            sleeps.add(millis);
            return shuttingDown;
        });
        Map<String, String> headers = new HashMap<>();
        headers.put(Message.ID, "msg-1");
        headers.put(Message.DESTINATION, "billingService");
        headers.put("command_type", "external.Authorize");
        message = new MessageImpl("{\"amount\":500}", headers);
    }

    /** A chain whose first {@code failures.length} invocations throw the given exceptions, then succeed. */
    private static MessageHandlerDecoratorChain chain(AtomicInteger calls, RuntimeException... failures) {
        return sm -> {
            int n = calls.getAndIncrement();
            if (n < failures.length) throw failures[n];
        };
    }

    private void accept(MessageHandlerDecoratorChain chain) {
        decorator.accept(new SubscriberIdAndMessage(SUBSCRIBER, message), chain);
    }

    private static RuntimeException contention() {
        return new CannotAcquireLockException("lock");
    }

    private static RuntimeException infraDown() {
        return new CannotGetJdbcConnectionException("db down");
    }

    @Test
    void success_runs_the_chain_once() {
        AtomicInteger calls = new AtomicInteger();
        accept(chain(calls));

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
        verifyNoInteractions(parking);
    }

    @Test
    void contention_is_retried_until_it_succeeds() {
        AtomicInteger calls = new AtomicInteger();
        accept(chain(calls, contention(), contention()));

        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2).allMatch(ms -> ms >= 0 && ms <= props.getContentionMaxDelay().toMillis());
        verifyNoInteractions(parking);
    }

    @Test
    void contention_past_its_budget_is_parked_and_the_consumer_continues() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException[] always = {contention(), contention(), contention(), contention(), contention(), contention()};

        accept(chain(calls, always));

        assertThat(calls).hasValue(5);
        verify(parking).park(eq(SUBSCRIBER), eq(message), eq(FailureClass.CONTENTION), any(), eq(5));
        verify(metrics).parked(SUBSCRIBER, FailureClass.CONTENTION);
    }

    @Test
    void poison_is_parked_after_three_attempts() {
        AtomicInteger calls = new AtomicInteger();
        NullPointerException npe = new NullPointerException("bug");

        accept(chain(calls, npe, npe, npe, npe));

        assertThat(calls).hasValue(3);
        verify(parking).park(eq(SUBSCRIBER), eq(message), eq(FailureClass.POISON), eq(npe), eq(3));
    }

    @Test
    void infrastructure_outage_waits_without_limit_and_never_parks() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException[] outage = new RuntimeException[50];
        java.util.Arrays.fill(outage, infraDown());

        accept(chain(calls, outage));

        assertThat(calls).hasValue(51);
        assertThat(sleeps).hasSize(50);
        assertThat(sleeps.get(0)).isEqualTo(1_000);
        assertThat(sleeps.get(49)).isEqualTo(30_000);
        verifyNoInteractions(parking);
        verify(metrics, times(50)).infraWaiting(1);
        verify(metrics, times(50)).infraWaiting(-1);
    }

    @Test
    void infrastructure_waits_do_not_use_up_the_contention_budget() {
        AtomicInteger calls = new AtomicInteger();
        accept(chain(calls, contention(), infraDown(), infraDown(), contention(), contention(), contention()));

        assertThat(calls).hasValue(7);
        verifyNoInteractions(parking);
    }

    @Test
    void every_attempt_sees_the_message_as_received() {
        List<String> seen = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        MessageHandlerDecoratorChain mutating = sm -> {
            seen.add(sm.getMessage().getHeaders().get("command_type") + "|" + sm.getMessage().getPayload());
            sm.getMessage().setHeader("command_type", "com.vab.Mapped");   // Tram rewrites headers in place
            sm.getMessage().setPayload("changed");
            if (calls.getAndIncrement() < 2) throw contention();
        };

        accept(mutating);

        assertThat(seen).containsOnly("external.Authorize|{\"amount\":500}").hasSize(3);
    }

    @Test
    void parked_message_is_the_original_not_a_mutated_copy() {
        MessageHandlerDecoratorChain mutating = sm -> {
            sm.getMessage().setHeader("command_type", "mutated");
            throw new IllegalArgumentException("bad payload");
        };

        accept(mutating);

        assertThat(message.getHeaders().get("command_type")).isEqualTo("external.Authorize");
        verify(parking).park(eq(SUBSCRIBER), eq(message), eq(FailureClass.POISON), any(), eq(3));
    }

    @Test
    void redriven_message_for_another_subscriber_is_skipped() {
        message.setHeader(MessagingHeaders.REDRIVE_TARGET, "orderServiceProjector");
        AtomicInteger calls = new AtomicInteger();

        accept(chain(calls));

        assertThat(calls).hasValue(0);
    }

    @Test
    void redriven_message_for_this_subscriber_is_processed() {
        message.setHeader(MessagingHeaders.REDRIVE_TARGET, SUBSCRIBER);
        AtomicInteger calls = new AtomicInteger();

        accept(chain(calls));

        assertThat(calls).hasValue(1);
    }

    @Test
    void park_failure_that_is_not_an_outage_stops_the_subscription_instead_of_losing_the_message() {
        doThrow(new IllegalStateException("relation parked_message does not exist"))
                .when(parking).park(any(), any(), any(), any(), anyInt());
        AtomicInteger calls = new AtomicInteger();
        NullPointerException npe = new NullPointerException("bug");

        assertThatThrownBy(() -> accept(chain(calls, npe, npe, npe)))
                .isInstanceOf(MessageHandlingAbortedException.class)
                .hasCause(npe);
    }

    @Test
    void park_during_an_outage_waits_then_runs_the_handler_again() {
        doThrow(infraDown()).doNothing().when(parking).park(any(), any(), any(), any(), anyInt());
        AtomicInteger calls = new AtomicInteger();
        NullPointerException npe = new NullPointerException("bug");

        accept(chain(calls, npe, npe, npe));   // 4th run succeeds after the failed park

        assertThat(calls).hasValue(4);
        verify(parking, times(1)).park(any(), any(), any(), any(), anyInt());
    }

    @Test
    void shutdown_while_waiting_aborts_so_the_message_is_redelivered() {
        shuttingDown = true;
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> accept(chain(calls, infraDown(), infraDown())))
                .isInstanceOf(MessageHandlingAbortedException.class);
        assertThat(calls).hasValue(1);
        verifyNoInteractions(parking);
    }

    @Test
    void interrupt_while_waiting_aborts_and_keeps_the_interrupt_flag() {
        decorator = new ResilientMessageHandlerDecorator(parking, props, metrics, millis -> {
            throw new InterruptedException();
        });
        AtomicInteger calls = new AtomicInteger();
        try {
            assertThatThrownBy(() -> accept(chain(calls, contention())))
                    .isInstanceOf(MessageHandlingAbortedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void jvm_errors_are_not_swallowed() {
        MessageHandlerDecoratorChain oom = sm -> { throw new OutOfMemoryError("test"); };

        assertThatThrownBy(() -> accept(oom)).isInstanceOf(OutOfMemoryError.class);
        verifyNoInteractions(parking);
    }

    @Test
    void sits_between_eventuates_receive_and_dedupe_decorators() {
        assertThat(decorator.getOrder()).isGreaterThan(100).isLessThan(200);
    }

    @Test
    void backoff_doubles_up_to_the_cap_and_never_overflows() {
        Duration one = Duration.ofSeconds(1), cap = Duration.ofSeconds(30);
        assertThat(ResilientMessageHandlerDecorator.backoff(one, cap, 1)).isEqualTo(1_000);
        assertThat(ResilientMessageHandlerDecorator.backoff(one, cap, 3)).isEqualTo(4_000);
        assertThat(ResilientMessageHandlerDecorator.backoff(one, cap, Integer.MAX_VALUE)).isEqualTo(30_000);
        assertThat(ResilientMessageHandlerDecorator.backoff(Duration.ZERO, cap, 10)).isZero();
    }

    @Test
    void jitter_stays_between_half_and_full_delay() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(ResilientMessageHandlerDecorator.jitter(100)).isBetween(50L, 100L);
        }
        assertThat(ResilientMessageHandlerDecorator.jitter(0)).isZero();
    }
}
