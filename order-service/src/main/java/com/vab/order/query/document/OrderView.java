package com.vab.order.query.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB read projection for the orders_v1 collection.
 *
 * Denormalized and screen-shaped — no JOINs, no live FKs.
 * The version field is the aggregate event version; used to skip
 * out-of-order events in the projector.
 */
@Document(collection = "orders_v1")
public class OrderView {

    @Id
    private String orderId;

    private String        subscriberId;
    private String        offerCode;
    private long          amount;
    private String        currency;
    private String        status;
    private Instant       placedAt;
    private Instant       confirmedAt;
    private String        version;  // last applied eventId (Int128 stringified, lexicographic order matches arrival)

    private List<TimelineEntry> timeline = new ArrayList<>();

    public OrderView() {}

    // ── Inner: timeline entry ─────────────────────────────────────────────

    public static class TimelineEntry {
        private Instant at;
        private String  status;

        public TimelineEntry() {}

        public TimelineEntry(Instant at, String status) {
            this.at     = at;
            this.status = status;
        }

        public Instant getAt()     { return at; }
        public String  getStatus() { return status; }
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public String            getOrderId()      { return orderId; }
    public String            getSubscriberId() { return subscriberId; }
    public String            getOfferCode()    { return offerCode; }
    public long              getAmount()       { return amount; }
    public String            getCurrency()     { return currency; }
    public String            getStatus()       { return status; }
    public Instant           getPlacedAt()     { return placedAt; }
    public Instant           getConfirmedAt()  { return confirmedAt; }
    public String            getVersion()      { return version; }
    public List<TimelineEntry> getTimeline()   { return timeline; }

    public void setOrderId(String orderId)           { this.orderId = orderId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }
    public void setOfferCode(String offerCode)       { this.offerCode = offerCode; }
    public void setAmount(long amount)               { this.amount = amount; }
    public void setCurrency(String currency)         { this.currency = currency; }
    public void setStatus(String status)             { this.status = status; }
    public void setPlacedAt(Instant placedAt)        { this.placedAt = placedAt; }
    public void setConfirmedAt(Instant confirmedAt)  { this.confirmedAt = confirmedAt; }
    public void setVersion(String version)           { this.version = version; }
    public void setTimeline(List<TimelineEntry> t)   { this.timeline = t; }

    public void addTimelineEntry(Instant at, String status) {
        this.timeline.add(new TimelineEntry(at, status));
    }
}
