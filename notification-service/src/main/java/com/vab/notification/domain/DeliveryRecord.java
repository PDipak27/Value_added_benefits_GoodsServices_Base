package com.vab.notification.domain;

import com.vab.notification.dispatch.Channel;
import com.vab.notification.dispatch.NotificationType;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * Persisted delivery status — one row per notification the service emits.
 * This is the read-back of "what did we tell the subscriber, and did it go out".
 */
@Entity
@Table(name = "delivery_log", schema = "notification")
public class DeliveryRecord {

    public enum Status { SENT, FAILED }

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 32, nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 16, nullable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Column(name = "recipient", length = 128)
    private String recipient;

    @Column(name = "provider_ref", length = 64)
    private String providerRef;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DeliveryRecord() {}

    public DeliveryRecord(String id, String orderId, NotificationType type, Channel channel,
                          Status status, String recipient, String providerRef, String body) {
        this.id          = id;
        this.orderId     = orderId;
        this.type        = type;
        this.channel     = channel;
        this.status      = status;
        this.recipient   = recipient;
        this.providerRef = providerRef;
        this.body        = body;
        this.createdAt   = Instant.now();
    }

    public String           getId()          { return id; }
    public String           getOrderId()     { return orderId; }
    public NotificationType getType()        { return type; }
    public Channel          getChannel()     { return channel; }
    public String           getRecipient()   { return recipient; }
    public Status           getStatus()      { return status; }
    public String           getProviderRef() { return providerRef; }
    public String           getBody()        { return body; }
    public Instant          getCreatedAt()   { return createdAt; }
}
