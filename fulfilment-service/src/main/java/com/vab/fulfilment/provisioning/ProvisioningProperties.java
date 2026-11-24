package com.vab.fulfilment.provisioning;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** {@code vab.fulfilment.provisioning.*} (Design/13 §6). */
@ConfigurationProperties("vab.fulfilment.provisioning")
public class ProvisioningProperties {

    /** From the first failed attempt: past it, the saga gets {@code OrderFulfilmentFailed(PROVISIONING_TIMEOUT)} → refund. */
    private Duration deadline = Duration.ofHours(2);

    /** Delay before attempt n+1 is {@code backoff[n-1]}; the last value repeats until the deadline. */
    private List<Duration> backoff = List.of(Duration.ofSeconds(30), Duration.ofMinutes(1),
            Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10));

    /** Max rows per poller pass. */
    private int batchSize = 10;

    /** How long a poller owns an IN_PROGRESS row; also the wait before the first revoke-by-order. */
    private Duration lease = Duration.ofSeconds(60);

    /** Retry interval for a revoke at OTT that failed. */
    private Duration revokeRetry = Duration.ofMinutes(1);

    /** How long a cancel that overtook its fulfil command is remembered. */
    private Duration tombstoneTtl = Duration.ofHours(2);

    public Duration delayAfter(int attempts) {
        if (backoff == null || backoff.isEmpty()) return Duration.ofMinutes(1);
        return backoff.get(Math.max(0, Math.min(attempts - 1, backoff.size() - 1)));
    }

    public Duration getDeadline() { return deadline; }
    public void setDeadline(Duration deadline) { this.deadline = deadline; }
    public List<Duration> getBackoff() { return backoff; }
    public void setBackoff(List<Duration> backoff) { this.backoff = backoff; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getLease() { return lease; }
    public void setLease(Duration lease) { this.lease = lease; }
    public Duration getRevokeRetry() { return revokeRetry; }
    public void setRevokeRetry(Duration revokeRetry) { this.revokeRetry = revokeRetry; }
    public Duration getTombstoneTtl() { return tombstoneTtl; }
    public void setTombstoneTtl(Duration tombstoneTtl) { this.tombstoneTtl = tombstoneTtl; }
}
