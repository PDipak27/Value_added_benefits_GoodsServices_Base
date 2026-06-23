package com.vab.events.fulfilment;

/**
 * Reply: DIGITAL_SUBSCRIPTION provisioning could not be completed against the
 * external {@code ott-service} after the participant's bounded in-call retries
 * (OTT unavailable, or a hard 422 rejection) — DD-27.
 *
 * <p>Distinct from {@link OrderFulfilmentFailed}: that reply means "forward-recover
 * (refund/reverse + release) → CANCELLED_REFUNDED" (DD-26). This one is OTT-only and
 * means "do <em>not</em> unwind — park the order in {@code FULFILMENT_FAILED} for an
 * admin to fix the external service and re-drive". Like its sibling it is post-pivot,
 * so it is a <em>success-outcome</em> reply (never {@code withFailure}); the saga must
 * branch on its content rather than complete the order.
 */
public class OrderProvisioningFailed {
    private String orderId;   // self-correlation for the DD-27 admin re-drive reply consumer
    private String reason;
    private String detail;

    public OrderProvisioningFailed() {}

    public OrderProvisioningFailed(String orderId, String reason, String detail) {
        this.orderId = orderId;
        this.reason  = reason;
        this.detail  = detail;
    }

    public String getOrderId() { return orderId; }
    public String getReason()  { return reason; }
    public String getDetail()  { return detail; }
}
