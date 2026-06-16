package com.vab.order.command.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Order aggregate — state-stored JPA entity (post-DD-14).
 *
 * <p>The aggregate's current state lives in one updatable row in
 * {@code orders.orders}; it is NOT event-sourced. Domain events are published
 * separately, in the same transaction, through the Eventuate Tram outbox
 * ({@code OrderCommandService} + {@code DomainEventPublisher}).
 *
 * <p>{@code @Version} gives optimistic locking and a monotonic per-order
 * sequence that is stamped onto each published event so the read-side projector
 * can discard out-of-order deliveries.
 */
@Entity
@Table(name = "orders", schema = "orders")
public class Order {

    /** Aggregate type used as the Tram event topic / projector subscription. */
    public static final String AGGREGATE_TYPE = "com.vab.order.command.domain.Order";

    @Id
    @Column(name = "order_id", length = 255)
    private String id;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "subscriber_id", nullable = false, length = 255)
    private String subscriberId;

    @Column(name = "offer_code", nullable = false, length = 255)
    private String offerCode;

    @Column(name = "product_type", length = 32)
    private String productType;

    @Column(name = "price_snapshot_id", length = 255)
    private String priceSnapshotId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "billing_mode", length = 32)
    private String billingMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "placed_at")
    private Instant placedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Cooperative cancel flag set by the user-cancel API; the saga's checkpoints read it (DD-26). */
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    // Fulfilment artifact — exactly one is populated at complete, per productType.
    @Column(name = "tracking_ref", length = 100)
    private String trackingRef;     // PHYSICAL_GOOD

    @Column(name = "activation_key", length = 100)
    private String activationKey;   // SOFTWARE_LICENSE

    @Column(name = "external_ref", length = 100)
    private String externalRef;     // DIGITAL_SUBSCRIPTION

    @Column(name = "failed_step", length = 64)
    private String failedStep;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    protected Order() {}  // JPA

    /** Factory for a freshly placed order. */
    public static Order place(String id, String subscriberId, String offerCode,
                              String productType, String priceSnapshotId, long amount,
                              String currency, String billingMode) {
        Order o = new Order();
        o.id              = id;
        o.subscriberId    = subscriberId;
        o.offerCode       = offerCode;
        o.productType     = productType;
        o.priceSnapshotId = priceSnapshotId;
        o.amount          = amount;
        o.currency        = currency;
        o.billingMode     = billingMode;
        o.status          = OrderStatus.PLACED;
        o.placedAt        = Instant.now();
        return o;
    }

    // ── State transitions ─────────────────────────────────────────────────

    /** Intermediate confirm: inventory settled and (PAY_NOW) authorized, before fulfilment. */
    public void confirm(Instant confirmedAt) {
        this.status      = OrderStatus.CONFIRMED;
        this.confirmedAt = confirmedAt;
    }

    /** Terminal complete: fulfilled and (PAY_NOW) captured; one artifact is non-null. */
    public void complete(Instant completedAt, String trackingRef,
                         String activationKey, String externalRef) {
        this.status        = OrderStatus.COMPLETED;
        this.completedAt   = completedAt;
        this.trackingRef   = trackingRef;
        this.activationKey = activationKey;
        this.externalRef   = externalRef;
    }

    public void fail(String failedStep, String reason) {
        this.status        = OrderStatus.FAILED;
        this.failedStep    = failedStep;
        this.failureReason = reason;
    }

    /**
     * Records a user's cancel intent (DD-26). Cooperative: this only sets a flag —
     * the running saga decides the outcome at its next checkpoint (rollback before
     * the pivot, forward-recovery after). Rejected once the order is terminal.
     */
    public void requestCancel() {
        if (isTerminal()) {
            throw new IllegalStateException("Order " + id + " is " + status + " and can no longer be cancelled");
        }
        this.cancelRequested = true;
    }

    /** Terminal cancel before the pivot: rolled back, nothing was charged (DD-26). */
    public void cancel(String reason) {
        this.status        = OrderStatus.CANCELLED;
        this.cancelledAt   = Instant.now();
        this.failedStep    = "USER_CANCEL";
        this.failureReason = reason;
    }

    /** Terminal unwind after the pivot via forward-recovery: refund/reverse + release (DD-26). */
    public void cancelRefunded(String reason) {
        this.status        = OrderStatus.CANCELLED_REFUNDED;
        this.cancelledAt   = Instant.now();
        this.failureReason = reason;
    }

    private boolean isTerminal() {
        return status == OrderStatus.COMPLETED
                || status == OrderStatus.FAILED
                || status == OrderStatus.CANCELLED
                || status == OrderStatus.CANCELLED_REFUNDED;
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public String      getId()              { return id; }
    public long        getVersion()         { return version; }
    public String      getSubscriberId()    { return subscriberId; }
    public String      getOfferCode()       { return offerCode; }
    public String      getProductType()     { return productType; }
    public String      getPriceSnapshotId() { return priceSnapshotId; }
    public long        getAmount()          { return amount; }
    public String      getCurrency()        { return currency; }
    public String      getBillingMode()     { return billingMode; }
    public OrderStatus getStatus()          { return status; }
    public Instant     getPlacedAt()        { return placedAt; }
    public Instant     getConfirmedAt()     { return confirmedAt; }
    public Instant     getCompletedAt()     { return completedAt; }
    public Instant     getCancelledAt()     { return cancelledAt; }
    public boolean     isCancelRequested()  { return cancelRequested; }
    public String      getTrackingRef()     { return trackingRef; }
    public String      getActivationKey()   { return activationKey; }
    public String      getExternalRef()     { return externalRef; }
    public String      getFailedStep()      { return failedStep; }
    public String      getFailureReason()   { return failureReason; }
}
