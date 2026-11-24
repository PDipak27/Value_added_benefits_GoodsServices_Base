package com.vab.messaging;

/** Metrics hooks of {@link ResilientMessageHandlerDecorator}. {@link #NOOP} when Micrometer is absent. */
public interface MessagingMetrics {

    /** One failed attempt (retried, waited on, or about to be parked). */
    void failure(String subscriberId, FailureClass failureClass);

    void parked(String subscriberId, FailureClass failureClass);

    /** Called with +1 when a handler thread starts waiting for infrastructure, -1 when it stops. */
    void infraWaiting(int delta);

    MessagingMetrics NOOP = new MessagingMetrics() {
        @Override public void failure(String subscriberId, FailureClass failureClass) {}
        @Override public void parked(String subscriberId, FailureClass failureClass) {}
        @Override public void infraWaiting(int delta) {}
    };
}
