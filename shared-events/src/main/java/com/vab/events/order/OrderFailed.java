package com.vab.events.order;

import io.eventuate.Event;

public class OrderFailed implements Event {
    private String failedStep;
    private String terminalReason;

    public OrderFailed() {}

    public OrderFailed(String failedStep, String terminalReason) {
        this.failedStep     = failedStep;
        this.terminalReason = terminalReason;
    }

    public String getFailedStep()     { return failedStep; }
    public String getTerminalReason() { return terminalReason; }
}
