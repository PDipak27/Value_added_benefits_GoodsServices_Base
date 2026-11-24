package com.vab.ott.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One partner notification in the outbox (Design/06 §8): written with the state change, delivered later. */
@Entity
@Table(name = "webhook_events", schema = "ott")
public class WebhookEvent {

    public enum Status { PENDING, DELIVERED, FAILED }

    @Id
    @Column(name = "id", length = 40)
    private String id;

    @Column(name = "event_type", length = 40, nullable = false)
    private String type;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected WebhookEvent() {}

    public WebhookEvent(String id, String type, String payload) {
        this.id      = id;
        this.type    = type;
        this.payload = payload;
    }

    public void delivered() {
        attempts++;
        status      = Status.DELIVERED;
        deliveredAt = Instant.now();
        lastError   = null;
    }

    /** A failed attempt: retry at {@code next}, or give up once {@code maxAttempts} is reached. */
    public void failed(String error, Instant next, int maxAttempts) {
        attempts++;
        lastError = error == null ? null : error.substring(0, Math.min(300, error.length()));
        if (attempts >= maxAttempts) status = Status.FAILED;
        else nextAttemptAt = next;
    }

    public String  getId()            { return id; }
    public String  getType()          { return type; }
    public String  getPayload()       { return payload; }
    public Status  getStatus()        { return status; }
    public int     getAttempts()      { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String  getLastError()     { return lastError; }
    public Instant getDeliveredAt()   { return deliveredAt; }
}
