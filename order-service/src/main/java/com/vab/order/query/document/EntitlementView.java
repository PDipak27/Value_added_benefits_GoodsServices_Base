package com.vab.order.query.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Read model for a subscriber's active benefit (DD pending §B1). One document per
 * COMPLETED order whose productType is a benefit type (DIGITAL_SUBSCRIPTION /
 * SOFTWARE_LICENSE); written by the projector on {@code OrderCompleted}.
 *
 * <p>Uniqueness "one active entitlement per subscriber-per-offer" is enforced by a
 * partial unique index on {@code (subscriberId, offerCode)} where status=ACTIVE
 * (see {@code EntitlementIndexConfig}); the projector upserts idempotently keyed
 * on {@code sourceOrderId}. {@code validFrom}/{@code validUntil} are populated in
 * Phase 2 (a null {@code validUntil} = perpetual).
 */
@Document(collection = "entitlements_v1")
public class EntitlementView {

    @Id
    private String  entitlementId;
    private String  subscriberId;
    private String  offerCode;
    private String  productType;
    private String  status;         // ACTIVE | REVOKED
    private String  sourceOrderId;
    private String  externalRef;    // DIGITAL_SUBSCRIPTION (OTT)
    private String  activationKey;  // SOFTWARE_LICENSE
    private Instant activatedAt;
    private Instant validFrom;      // Phase 2
    private Instant validUntil;     // Phase 2 — null = perpetual
    private long    version;        // source order's aggregate @Version (monotonic guard)

    public String  getEntitlementId() { return entitlementId; }
    public String  getSubscriberId()  { return subscriberId; }
    public String  getOfferCode()     { return offerCode; }
    public String  getProductType()   { return productType; }
    public String  getStatus()        { return status; }
    public String  getSourceOrderId() { return sourceOrderId; }
    public String  getExternalRef()   { return externalRef; }
    public String  getActivationKey() { return activationKey; }
    public Instant getActivatedAt()   { return activatedAt; }
    public Instant getValidFrom()     { return validFrom; }
    public Instant getValidUntil()    { return validUntil; }
    public long    getVersion()       { return version; }

    public void setEntitlementId(String entitlementId) { this.entitlementId = entitlementId; }
    public void setSubscriberId(String subscriberId)   { this.subscriberId = subscriberId; }
    public void setOfferCode(String offerCode)         { this.offerCode = offerCode; }
    public void setProductType(String productType)     { this.productType = productType; }
    public void setStatus(String status)               { this.status = status; }
    public void setSourceOrderId(String sourceOrderId) { this.sourceOrderId = sourceOrderId; }
    public void setExternalRef(String externalRef)     { this.externalRef = externalRef; }
    public void setActivationKey(String activationKey) { this.activationKey = activationKey; }
    public void setActivatedAt(Instant activatedAt)    { this.activatedAt = activatedAt; }
    public void setValidFrom(Instant validFrom)        { this.validFrom = validFrom; }
    public void setValidUntil(Instant validUntil)      { this.validUntil = validUntil; }
    public void setVersion(long version)               { this.version = version; }
}
