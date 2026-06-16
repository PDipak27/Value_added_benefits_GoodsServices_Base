package com.vab.fulfilment.domain;

import com.vab.events.common.ProductType;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * One row per fulfilment, written when a {@code FulfilOrderCommand} succeeds. It
 * is the auditable record of <em>what artifact was delivered</em> for an order and
 * the handle the {@code CancelFulfilmentCommand} compensation acts on
 * ({@code fulfilmentRef}). Exactly one of {@code trackingRef} /
 * {@code activationKey} / {@code externalRef} is populated, per {@code productType}.
 */
@Entity
@Table(name = "fulfilments", schema = "fulfilment")
public class FulfilmentRecord {

    public enum Status { FULFILLED, CANCELLED }

    @Id
    @Column(name = "fulfilment_ref", length = 64)
    private String fulfilmentRef;

    @Column(name = "order_id", length = 64, nullable = false)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", length = 20, nullable = false)
    private ProductType productType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status = Status.FULFILLED;

    @Column(name = "tracking_ref", length = 100)
    private String trackingRef;

    @Column(name = "activation_key", length = 100)
    private String activationKey;

    @Column(name = "external_ref", length = 100)
    private String externalRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected FulfilmentRecord() {}

    public FulfilmentRecord(String fulfilmentRef, String orderId, ProductType productType,
                            String trackingRef, String activationKey, String externalRef) {
        this.fulfilmentRef = fulfilmentRef;
        this.orderId       = orderId;
        this.productType   = productType;
        this.trackingRef   = trackingRef;
        this.activationKey = activationKey;
        this.externalRef   = externalRef;
        this.createdAt     = Instant.now();
    }

    public void cancel() { this.status = Status.CANCELLED; }

    public String      getFulfilmentRef() { return fulfilmentRef; }
    public String      getOrderId()       { return orderId; }
    public ProductType getProductType()   { return productType; }
    public Status      getStatus()        { return status; }
    public String      getTrackingRef()   { return trackingRef; }
    public String      getActivationKey() { return activationKey; }
    public String      getExternalRef()   { return externalRef; }
    public Instant     getCreatedAt()     { return createdAt; }
}
