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
    private String        productType;
    private long          amount;
    private String        currency;
    private String        status;
    private Instant       placedAt;
    private Instant       confirmedAt;
    private Instant       completedAt;
    private long          version;  // aggregate @Version of the last applied event (monotonic per order)

    private Fulfilment    fulfilment;   // set on complete; exactly one ref populated, per productType

    private List<TimelineEntry> timeline = new ArrayList<>();

    public OrderView() {}

    // ── Inner: fulfilment artifact ────────────────────────────────────────

    /** Denormalized delivery artifact — exactly one ref is non-null, per type. */
    public static class Fulfilment {
        private String productType;
        private String trackingRef;    // PHYSICAL_GOOD
        private String activationKey;  // SOFTWARE_LICENSE
        private String externalRef;    // DIGITAL_SUBSCRIPTION

        public Fulfilment() {}

        public Fulfilment(String productType, String trackingRef,
                          String activationKey, String externalRef) {
            this.productType   = productType;
            this.trackingRef   = trackingRef;
            this.activationKey = activationKey;
            this.externalRef   = externalRef;
        }

        public String getProductType()   { return productType; }
        public String getTrackingRef()   { return trackingRef; }
        public String getActivationKey() { return activationKey; }
        public String getExternalRef()   { return externalRef; }
    }

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
    public String            getProductType()  { return productType; }
    public long              getAmount()       { return amount; }
    public String            getCurrency()     { return currency; }
    public String            getStatus()       { return status; }
    public Instant           getPlacedAt()     { return placedAt; }
    public Instant           getConfirmedAt()  { return confirmedAt; }
    public Instant           getCompletedAt()  { return completedAt; }
    public long              getVersion()      { return version; }
    public Fulfilment        getFulfilment()   { return fulfilment; }
    public List<TimelineEntry> getTimeline()   { return timeline; }

    public void setOrderId(String orderId)           { this.orderId = orderId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }
    public void setOfferCode(String offerCode)       { this.offerCode = offerCode; }
    public void setProductType(String productType)   { this.productType = productType; }
    public void setAmount(long amount)               { this.amount = amount; }
    public void setCurrency(String currency)         { this.currency = currency; }
    public void setStatus(String status)             { this.status = status; }
    public void setPlacedAt(Instant placedAt)        { this.placedAt = placedAt; }
    public void setConfirmedAt(Instant confirmedAt)  { this.confirmedAt = confirmedAt; }
    public void setCompletedAt(Instant completedAt)  { this.completedAt = completedAt; }
    public void setVersion(long version)             { this.version = version; }
    public void setFulfilment(Fulfilment fulfilment) { this.fulfilment = fulfilment; }
    public void setTimeline(List<TimelineEntry> t)   { this.timeline = t; }

    public void addTimelineEntry(Instant at, String status) {
        this.timeline.add(new TimelineEntry(at, status));
    }
}
