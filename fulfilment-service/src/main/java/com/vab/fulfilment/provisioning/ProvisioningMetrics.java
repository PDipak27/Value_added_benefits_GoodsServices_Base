package com.vab.fulfilment.provisioning;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.function.ToDoubleFunction;

/**
 * Design/13 §9 provisioning signals. Prometheus names:
 * <ul>
 *   <li>{@code vab_provisioning_outcome_total{outcome}}: provisioned, provisioned_on_retry, deferred, rejected,
 *       timeout, cancelled. Alert on a rising {@code rejected} rate: nobody reviews rejections any more.</li>
 *   <li>{@code vab_provisioning_tasks{status}} and {@code vab_provisioning_oldest_task_seconds{status}}:
 *       open work and how long it's been waiting (alert on old RETRYING / REVOKE_OWED rows).</li>
 * </ul>
 */
@Component
public class ProvisioningMetrics {

    private final MeterRegistry registry;

    public ProvisioningMetrics(MeterRegistry registry, ProvisioningTaskStore tasks) {
        this.registry = registry;
        for (ProvisioningTask.Status status : ProvisioningTask.Status.values()) {
            gauge("vab.provisioning.tasks", status, tasks, t -> t.count(status));
            gauge("vab.provisioning.oldest.task.seconds", status, tasks, t -> t.oldestAgeSeconds(status));
        }
    }

    public void outcome(String outcome) {
        Counter.builder("vab.provisioning.outcome").tag("outcome", outcome).register(registry).increment();
    }

    private void gauge(String name, ProvisioningTask.Status status, ProvisioningTaskStore tasks,
                       ToDoubleFunction<ProvisioningTaskStore> query) {
        Gauge.builder(name, tasks, t -> {
                    try {
                        return query.applyAsDouble(t);
                    } catch (RuntimeException dbUnavailable) {
                        return Double.NaN;   // a scrape while the DB is down must not throw
                    }
                })
                .tag("status", status.name())
                .register(registry);
    }
}
