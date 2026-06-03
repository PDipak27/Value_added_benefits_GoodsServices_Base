package com.vab.order.command.domain;

public class FailOrderCommand implements OrderCommand {
    private String failedStep;
    private String reason;

    public FailOrderCommand() {}

    public FailOrderCommand(String failedStep, String reason) {
        this.failedStep = failedStep;
        this.reason     = reason;
    }

    public String getFailedStep() { return failedStep; }
    public String getReason()     { return reason; }
}
