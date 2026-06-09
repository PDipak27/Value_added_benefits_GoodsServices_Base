package com.vab.events.order;

import io.eventuate.tram.events.common.DomainEvent;

/**
 * Published (via the Tram outbox) when the saga reaches a terminal failure.
 * {@code version} is the aggregate's JPA {@code @Version} at publish time.
 */
public class OrderFailed implements DomainEvent {
    private String failedStep;
    private String terminalReason;
    private long   version;

    public OrderFailed() {}

    public OrderFailed(String failedStep, String terminalReason, long version) {
        this.failedStep     = failedStep;
        this.terminalReason = terminalReason;
        this.version        = version;
    }

    public String getFailedStep()     { return failedStep; }
    public String getTerminalReason() { return terminalReason; }
    public long   getVersion()        { return version; }
}
