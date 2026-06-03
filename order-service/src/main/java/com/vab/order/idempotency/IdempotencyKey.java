package com.vab.order.idempotency;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name  = "idempotency_keys",
       schema = "orders",
       uniqueConstraints = @UniqueConstraint(columnNames = {"subscriber_id", "idempotency_key"}))
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscriber_id",  nullable = false, length = 255)
    private String subscriberId;

    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;

    @Column(name = "order_id", nullable = false, length = 255)
    private String orderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public IdempotencyKey() {}

    public IdempotencyKey(String subscriberId, String idempotencyKey, String orderId) {
        this.subscriberId   = subscriberId;
        this.idempotencyKey = idempotencyKey;
        this.orderId        = orderId;
    }

    public Long    getId()             { return id; }
    public String  getSubscriberId()   { return subscriberId; }
    public String  getIdempotencyKey() { return idempotencyKey; }
    public String  getOrderId()        { return orderId; }
    public Instant getCreatedAt()      { return createdAt; }
}
