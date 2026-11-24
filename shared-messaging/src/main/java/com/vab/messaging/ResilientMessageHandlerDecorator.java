package com.vab.messaging;

import io.eventuate.tram.consumer.common.MessageHandlerDecorator;
import io.eventuate.tram.consumer.common.MessageHandlerDecoratorChain;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.SubscriberIdAndMessage;
import io.eventuate.tram.messaging.consumer.BuiltInMessageHandlerDecoratorOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Keeps every Eventuate Tram subscription alive (Design/13 §4). Without it, one exception from any handler
 * ends the subscription until the JVM restarts (13 §2 F2–F7).
 *
 * <p><b>Placement.</b> Order {@value #ORDER}: after {@code PrePostReceive} (100) and before
 * {@code DuplicateDetecting} (200), which opens the message's DB transaction. So every attempt runs the
 * whole chain again: a fresh transaction, a fresh dedupe check, the handler, the outbox inserts.
 *
 * <p><b>Per failed attempt</b>, the message's headers and payload are first restored to what was received
 * (Tram's dispatchers rewrite headers in place, e.g. {@code COMMAND_TYPE}). Then, by {@link FailureClassifier}:
 * <ul>
 *   <li>{@link FailureClass#INFRA_DOWN}: wait (1 s doubling to 30 s) and try again, without limit. Never parks.</li>
 *   <li>{@link FailureClass#CONTENTION}: up to 5 attempts with jittered backoff (≈2 s in total), then park.</li>
 *   <li>{@link FailureClass#POISON}: up to 3 attempts, then park.</li>
 * </ul>
 * After parking, {@link #accept} returns normally: Eventuate commits the offset and the subscription moves on.
 *
 * <p><b>Never loses a message.</b> {@link #accept} only returns normally once the message was processed,
 * parked, or (for a re-driven message) is addressed to another subscriber. In every other case it throws:
 * shutdown or interrupt while waiting, a park failure that isn't an outage, or a JVM {@link Error} (not
 * caught). Then Eventuate ends the subscription without committing, and the message is redelivered after
 * the restart. The Eventuate subscription health indicator reports that state.
 *
 * <p>Retries are safe because every attempt's transaction rolled back completely (Tram dedupe + outbox in one
 * tx). A handler that calls a remote service must guard that call itself (timeouts, circuit breaker; Design/13
 * invariant 4). A connection error escaping from a handler is treated as INFRA_DOWN and would block.
 */
public class ResilientMessageHandlerDecorator implements MessageHandlerDecorator, DisposableBean {

    public static final int ORDER = 150;

    private static final Logger log = LoggerFactory.getLogger(ResilientMessageHandlerDecorator.class);

    /** Sleeps for the given time; returns {@code true} if the application is shutting down. */
    interface Sleeper {
        boolean sleep(long millis) throws InterruptedException;
    }

    private final ParkedMessageStore parking;
    private final MessagingResilienceProperties props;
    private final MessagingMetrics metrics;
    private final CountDownLatch shutdown = new CountDownLatch(1);
    private final Sleeper sleeper;

    public ResilientMessageHandlerDecorator(ParkedMessageStore parking, MessagingResilienceProperties props,
                                            MessagingMetrics metrics) {
        this(parking, props, metrics, null);
    }

    /** {@code sleeper == null} → real waiting, interruptible by {@link #destroy()}. */
    ResilientMessageHandlerDecorator(ParkedMessageStore parking, MessagingResilienceProperties props,
                                     MessagingMetrics metrics, Sleeper sleeper) {
        // Eventuate's built-in orders are mutable statics; fail fast if an upgrade moves them around us.
        if (!(BuiltInMessageHandlerDecoratorOrder.PRE_POST_RECEIVE_MESSAGE_HANDLER_DECORATOR < ORDER
                && ORDER < BuiltInMessageHandlerDecoratorOrder.DUPLICATE_DETECTING_MESSAGE_HANDLER_DECORATOR)) {
            throw new IllegalStateException("ResilientMessageHandlerDecorator order " + ORDER
                    + " must lie between Eventuate's PrePostReceive and DuplicateDetecting decorators");
        }
        this.parking = parking;
        this.props   = props;
        this.metrics = metrics == null ? MessagingMetrics.NOOP : metrics;
        this.sleeper = sleeper != null ? sleeper : millis -> shutdown.await(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void accept(SubscriberIdAndMessage subscriberIdAndMessage, MessageHandlerDecoratorChain chain) {
        Message message     = subscriberIdAndMessage.getMessage();
        String subscriberId = subscriberIdAndMessage.getSubscriberId();

        String target = header(message, MessagingHeaders.REDRIVE_TARGET);
        if (target != null && !target.equals(subscriberId)) {
            log.debug("Skipping re-driven message {} addressed to {} (this subscriber is {})",
                    messageId(message), target, subscriberId);
            return;
        }

        Snapshot received = Snapshot.of(message);
        int contentionFailures = 0;
        int poisonFailures     = 0;
        int infraWaits         = 0;

        while (true) {
            try {
                chain.invokeNext(subscriberIdAndMessage);
                if (contentionFailures + poisonFailures + infraWaits > 0) {
                    log.info("Message {} for {} processed after {} contention, {} other failure(s) and {} infrastructure wait(s)",
                            messageId(message), subscriberId, contentionFailures, poisonFailures, infraWaits);
                }
                return;
            } catch (Exception e) {
                received.restore(message);
                FailureClass failureClass = classify(e);
                metrics.failure(subscriberId, failureClass);

                if (failureClass == FailureClass.INFRA_DOWN) {
                    infraWaits++;
                    waitForInfrastructure(subscriberId, message, e, infraWaits);
                    continue;
                }

                boolean contention = failureClass == FailureClass.CONTENTION;
                int failures = contention ? ++contentionFailures : ++poisonFailures;
                int maxAttempts = contention ? props.getContentionMaxAttempts() : props.getPoisonMaxAttempts();
                if (failures < maxAttempts) {
                    long delay = contention
                            ? jitter(backoff(props.getContentionInitialDelay(), props.getContentionMaxDelay(), failures))
                            : Math.max(0, props.getPoisonDelay().toMillis());
                    log.warn("Message {} for {} failed ({} attempt {}/{}), retrying in {} ms: {}",
                            messageId(message), subscriberId, failureClass, failures, maxAttempts, delay, summary(e));
                    pause(delay, e);
                    continue;
                }

                if (park(subscriberId, message, failureClass, e, contentionFailures + poisonFailures)) {
                    return;
                }
                // Parking itself hit an outage: wait, then run the handler again (it may succeed by then).
                infraWaits++;
                waitForInfrastructure(subscriberId, message, e, infraWaits);
            }
        }
    }

    /** Wakes every waiting handler thread; they throw, so their messages are redelivered after the restart. */
    @Override
    public void destroy() {
        shutdown.countDown();
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** @return true if parked; false if parking failed because the infrastructure is down (caller waits and retries). */
    private boolean park(String subscriberId, Message message, FailureClass failureClass, Exception failure, int attempts) {
        try {
            parking.park(subscriberId, message, failureClass, failure, attempts);
        } catch (Exception parkFailure) {
            if (classify(parkFailure) == FailureClass.INFRA_DOWN) {
                log.warn("Could not park message {} for {} (infrastructure unavailable): {}",
                        messageId(message), subscriberId, summary(parkFailure));
                return false;
            }
            failure.addSuppressed(parkFailure);
            log.error("Could not park message {} for {}. Stopping this subscription so the message is redelivered "
                    + "after a restart instead of being lost. Is the parked_message table missing?",
                    messageId(message), subscriberId, failure);
            throw new MessageHandlingAbortedException("Message " + messageId(message) + " for " + subscriberId
                    + " failed and could not be parked", failure);
        }
        metrics.parked(subscriberId, failureClass);
        log.error("PARKED message {} for {} after {} attempt(s) [{}]; the subscription continues. "
                + "Inspect / re-drive: GET /v1/ops/messages/parked", messageId(message), subscriberId, attempts,
                failureClass, failure);
        return true;
    }

    private void waitForInfrastructure(String subscriberId, Message message, Exception failure, int wait) {
        long delay = backoff(props.getInfraInitialDelay(), props.getInfraMaxDelay(), wait);
        if (wait == 1 || wait % 10 == 0) {
            log.warn("Infrastructure unavailable while handling message {} for {}; waiting {} ms (wait #{}): {}",
                    messageId(message), subscriberId, delay, wait, summary(failure));
        } else {
            log.debug("Still waiting for infrastructure: message {} for {}, wait #{}", messageId(message), subscriberId, wait);
        }
        metrics.infraWaiting(1);
        try {
            pause(delay, failure);
        } finally {
            metrics.infraWaiting(-1);
        }
    }

    private void pause(long millis, Exception cause) {
        boolean stopping;
        try {
            stopping = sleeper.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new MessageHandlingAbortedException("Interrupted while retrying a message; it will be redelivered", cause);
        }
        if (stopping) {
            throw new MessageHandlingAbortedException("Shutting down while retrying a message; it will be redelivered", cause);
        }
    }

    /** Doubles from {@code initial} for each further attempt, capped at {@code max}. Overflow-safe for any attempt count. */
    static long backoff(Duration initial, Duration max, int attempt) {
        long cap = Math.max(0, max.toMillis());
        long ms  = Math.min(Math.max(0, initial.toMillis()), cap);
        for (int i = 1; i < attempt && ms > 0 && ms < cap; i++) {
            ms = Math.min(cap, ms * 2);
        }
        return ms;
    }

    /** Uniformly in [ms/2, ms]: spreads retries of competing consumers. */
    static long jitter(long ms) {
        if (ms <= 1) return Math.max(0, ms);
        long half = ms / 2;
        return half + ThreadLocalRandom.current().nextLong(ms - half + 1);
    }

    private static FailureClass classify(Throwable t) {
        try {
            return FailureClassifier.classify(t);
        } catch (RuntimeException classifierFailure) {   // e.g. an exception whose getMessage() throws
            return FailureClass.POISON;
        }
    }

    private static String header(Message message, String name) {
        Map<String, String> headers = message.getHeaders();
        return headers == null ? null : headers.get(name);
    }

    private static String messageId(Message message) {
        String id = header(message, Message.ID);
        return id == null ? "<no-id>" : id;
    }

    /** Exception chain in one line, without stack traces (Eventuate's own decorators already log those). */
    private static String summary(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable c = t; c != null && depth < 5; c = c.getCause() == c ? null : c.getCause(), depth++) {
            if (depth > 0) sb.append(" <- ");
            sb.append(c.getClass().getSimpleName());
            if (c.getMessage() != null) {
                String m = c.getMessage().replaceAll("\\s+", " ");
                sb.append(": ").append(m.length() > 200 ? m.substring(0, 200) + "…" : m);
            }
        }
        return sb.toString();
    }

    /** The message as received, so each attempt starts from the same headers and payload. */
    private record Snapshot(Map<String, String> headers, String payload) {
        static Snapshot of(Message m) {
            return new Snapshot(m.getHeaders() == null ? null : new HashMap<>(m.getHeaders()), m.getPayload());
        }

        void restore(Message m) {
            m.setHeaders(headers == null ? null : new HashMap<>(headers));
            m.setPayload(payload);
        }
    }
}
