package com.vab.events.billing;

import io.eventuate.tram.commands.common.Command;

/**
 * Saga compensation (PAY_NOW): release an authorisation that will never be captured. Billing voids it at
 * the provider, or owes the void and retries it, and always replies success.
 */
public class VoidPaymentCommand implements Command {
    private String authId;
    private String reason;

    public VoidPaymentCommand() {}

    public VoidPaymentCommand(String authId, String reason) {
        this.authId = authId;
        this.reason = reason;
    }

    public String getAuthId() { return authId; }
    public String getReason() { return reason; }
}
