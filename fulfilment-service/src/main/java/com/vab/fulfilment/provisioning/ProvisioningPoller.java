package com.vab.fulfilment.provisioning;

import com.vab.fulfilment.command.OttProvisioningService;
import com.vab.fulfilment.command.OttProvisioningService.Attempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Drives open provisioning tasks (Design/13 §6). One always-on scheduled bean per instance. Several replicas
 * are safe: rows are claimed with {@code FOR UPDATE SKIP LOCKED} and a lease.
 *
 * <ol>
 *   <li>Housekeeping: reclaim rows whose poller died; drop expired cancel tombstones.</li>
 *   <li>Pass 1, decisions: cancelled or past-deadline rows get their reply. <b>Runs even while OTT is down</b>,
 *       because it needs no OTT call.</li>
 *   <li>Pass 2, only while the {@code ott} breaker isn't open: owed revokes, then due attempts. Each attempt
 *       is claim (short tx) → OTT call (no tx, no DB connection held) → finish (short tx).</li>
 * </ol>
 * A failure in one step is logged and the rest of the tick still runs; the next tick retries.
 */
@Component
public class ProvisioningPoller {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningPoller.class);

    private final ProvisioningTaskService service;
    private final OttProvisioningService provisioning;
    private final String instanceId = "poller-" + UUID.randomUUID();

    public ProvisioningPoller(ProvisioningTaskService service, OttProvisioningService provisioning) {
        this.service      = service;
        this.provisioning = provisioning;
    }

    @Scheduled(fixedDelayString = "${vab.fulfilment.provisioning.poll-interval:5s}",
               initialDelayString = "${vab.fulfilment.provisioning.poll-interval:5s}")
    public void tick() {
        step("reclaim expired leases", () -> {
            int n = service.reclaimExpiredLeases();
            if (n > 0) log.warn("Reclaimed {} provisioning task(s) from a poller that stopped mid-attempt", n);
        });
        step("delete expired tombstones", service::deleteExpiredTombstones);
        step("resolve cancelled / timed-out tasks", service::resolveDecisions);

        if (!provisioning.isOttCallPermitted()) {
            log.debug("OTT circuit open: skipping provisioning attempts and revokes this tick");
            return;
        }
        step("retry owed revokes", this::retryOwedRevokes);
        step("provisioning attempts", this::attemptDue);
    }

    private void attemptDue() {
        List<ProvisioningTask> claimed = service.claimDue(instanceId);
        for (ProvisioningTask task : claimed) {
            Attempt attempt;
            try {
                attempt = provisioning.attempt(task.command());
            } catch (RuntimeException e) {
                // attempt() maps every HTTP failure to a result; this is a bug. The lease expires and the
                // row is reclaimed (as uncertain), so nothing is lost.
                log.error("Provisioning attempt crashed: orderId={}", task.orderId(), e);
                continue;
            }
            try {
                service.finish(task, attempt, instanceId);
            } catch (RuntimeException e) {
                log.error("Could not record provisioning outcome: orderId={} (lease will expire and it is retried)",
                        task.orderId(), e);
            }
        }
    }

    private void retryOwedRevokes() {
        for (String orderId : service.claimRevokesDue()) {
            if (provisioning.revokeByOrder(orderId)) {
                step("record revoke " + orderId, () -> service.revokeDone(orderId));
            } else {
                log.warn("Owed OTT revoke still failing: orderId={} (retried after the lease)", orderId);
            }
        }
    }

    private void step(String name, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("Provisioning poller step '{}' failed: {}", name, e.toString());
        }
    }
}
