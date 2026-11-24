package com.vab.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer implementation. Prometheus names: {@code vab_messaging_failures_total{subscriber,class}},
 * {@code vab_messaging_parked_total{subscriber,class}}, {@code vab_messaging_infra_waiting}.
 */
class MicrometerMessagingMetrics implements MessagingMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger infraWaiting = new AtomicInteger();

    MicrometerMessagingMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("vab.messaging.infra.waiting", infraWaiting, AtomicInteger::get)
                .description("Message handler threads currently waiting for the database/Mongo to come back")
                .register(registry);
    }

    @Override
    public void failure(String subscriberId, FailureClass failureClass) {
        counter("vab.messaging.failures", subscriberId, failureClass).increment();
    }

    @Override
    public void parked(String subscriberId, FailureClass failureClass) {
        counter("vab.messaging.parked", subscriberId, failureClass).increment();
    }

    @Override
    public void infraWaiting(int delta) {
        infraWaiting.addAndGet(delta);
    }

    private Counter counter(String name, String subscriberId, FailureClass failureClass) {
        return Counter.builder(name)
                .tag("subscriber", subscriberId == null ? "unknown" : subscriberId)
                .tag("class", failureClass.name())
                .register(registry);   // idempotent: returns the existing counter
    }
}
