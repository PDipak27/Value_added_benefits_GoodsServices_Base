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

    @Column(name = "failed_step", length = 64)
    private String failedStep;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    protected Order() {}  // JPA

    /** Factory for a freshly placed order. */
    public static Order place(String id, String subscriberId, String offerCode,
                              String priceSnapshotId, long amount, String currency,
                              String billingMode) {
        Order o = new Order();
        o.id              = id;
        o.subscriberId    = subscriberId;
        o.offerCode       = offerCode;
        o.priceSnapshotId = priceSnapshotId;
        o.amount          = amount;
        o.currency        = currency;
        o.billingMode     = billingMode;
        o.status          = OrderStatus.PLACED;
        o.placedAt        = Instant.now();
        return o;
    }

    // ── State transitions ─────────────────────────────────────────────────

    public void confirm(Instant confirmedAt) {
        this.status      = OrderStatus.CONFIRMED;
        this.confirmedAt = confirmedAt;
    }

    public void fail(String failedStep, String reason) {
        this.status        = OrderStatus.FAILED;
        this.failedStep    = failedStep;
        this.failureReason = reason;
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public String      getId()              { return id; }
    public long        getVersion()         { return version; }
    public String      getSubscriberId()    { return subscriberId; }
    public String      getOfferCode()       { return offerCode; }
    public String      getPriceSnapshotId() { return priceSnapshotId; }
    public long        getAmount()          { return amount; }
    public String      getCurrency()        { return currency; }
    public String      getBillingMode()     { return billingMode; }
    public OrderStatus getStatus()          { return status; }
    public Instant     getPlacedAt()        { return placedAt; }
    public Instant     getConfirmedAt()     { return confirmedAt; }
    public String      getFailedStep()      { return failedStep; }
    public String      getFailureReason()   { return failureReason; }
}
