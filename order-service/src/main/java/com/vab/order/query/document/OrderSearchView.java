package com.vab.order.query.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Ops-dashboard read model (§B3). A flattened, timeline-free shape over the SAME
 * order event stream as {@code orders_v1}, demonstrating "one event stream → many
 * read shapes" (iter-3 §8.3). Maintained by its own projector + consumer group
 * ({@code orderSearchProjector}), independently of {@code orders_v1}, and indexed
 * on {@code status}/{@code offerCode}/{@code placedAt} for ops filtering.
 */
@Document(collection = "order_search_v1")
public class OrderSearchView {

    @Id
    private String  orderId;
    private String  subscriberId;
    private String  offerCode;
    private String  productType;
    private long    amount;
    private String  currency;
    private String  billingMode;
    private String  status;
    private Instant placedAt;
    private long    version;

    public String  getOrderId()      { return orderId; }
    public String  getSubscriberId() { return subscriberId; }
    public String  getOfferCode()    { return offerCode; }
    public String  getProductType()  { return productType; }
    public long    getAmount()       { return amount; }
    public String  getCurrency()     { return currency; }
    public String  getBillingMode()  { return billingMode; }
    public String  getStatus()       { return status; }
    public Instant getPlacedAt()     { return placedAt; }
    public long    getVersion()      { return version; }

    public void setOrderId(String orderId)           { this.orderId = orderId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }
    public void setOfferCode(String offerCode)       { this.offerCode = offerCode; }
    public void setProductType(String productType)   { this.productType = productType; }
    public void setAmount(long amount)               { this.amount = amount; }
    public void setCurrency(String currency)         { this.currency = currency; }
    public void setBillingMode(String billingMode)   { this.billingMode = billingMode; }
    public void setStatus(String status)             { this.status = status; }
    public void setPlacedAt(Instant placedAt)        { this.placedAt = placedAt; }
    public void setVersion(long version)             { this.version = version; }
}
