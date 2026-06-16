package com.vab.events.fulfilment;

/**
 * Reply: fulfilment failed for a <em>non-transient</em> reason (route closed,
 * damaged good, provisioning permanently refused). Fulfilment runs after the
 * pivot, so the saga must <em>not</em> compensate; this is therefore a
 * <em>success-outcome</em> branch reply (not {@code withFailure}). The
 * orchestrator records it, flips the saga onto its forward-recovery branch
 * (refund / reverse-ledger then release inventory) and ends the order in
 * {@code CANCELLED_REFUNDED} (DD-26). A genuinely transient error would instead
 * be retried inside the participant before any reply is sent.
 */
public class OrderFulfilmentFailed {
    private String reason;
    private String detail;

    public OrderFulfilmentFailed() {}

    public OrderFulfilmentFailed(String reason, String detail) {
        this.reason = reason;
        this.detail = detail;
    }

    public String getReason() { return reason; }
    public String getDetail() { return detail; }
}
