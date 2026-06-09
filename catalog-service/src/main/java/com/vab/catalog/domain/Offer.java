package com.vab.catalog.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An offer in the catalog, with its current price snapshot and the eligibility
 * constraints a subscriber must satisfy to purchase it (DD-16).
 *
 * <p>Stored as a MongoDB document. Offers are polymorphic across categories
 * (DIGITAL / PHYSICAL / SLOT) and their eligibility dimensions evolve often, so
 * a document model lets the shape change without per-attribute schema migrations.
 * Enums are persisted as their string names by the default Mongo mapping.
 *
 * <p>Null/blank constraint fields mean "no restriction on that dimension".
 * {@code priceSnapshotId} references an immutable price snapshot by id — orders
 * copy it at placement, so there is no live cross-store reference to maintain.
 */
@Document(collection = "offers")
public class Offer {

    @Id
    private String offerCode;

    private String name;
    private String description;

    /** DIGITAL | PHYSICAL | SLOT — the inventory family this offer draws on. */
    private String category;

    private long   amount;
    private String currency;
    private String priceSnapshotId;

    private OfferStatus status = OfferStatus.PUBLISHED;

    // ── Eligibility constraints (null = unconstrained) ──────────────────────

    private PlanTier minPlanTier;

    /** Comma-separated region codes; null/blank = all regions. */
    private String  allowedRegions;

    private Integer maxDeviceAgeMonths;

    private KycLevel minKycLevel;

    protected Offer() {}  // Mongo mapping

    public Offer(String offerCode, String name, String description, String category,
                 long amount, String currency, String priceSnapshotId, OfferStatus status,
                 PlanTier minPlanTier, String allowedRegions, Integer maxDeviceAgeMonths,
                 KycLevel minKycLevel) {
        this.offerCode          = offerCode;
        this.name               = name;
        this.description        = description;
        this.category           = category;
        this.amount             = amount;
        this.currency           = currency;
        this.priceSnapshotId    = priceSnapshotId;
        this.status             = status;
        this.minPlanTier        = minPlanTier;
        this.allowedRegions     = allowedRegions;
        this.maxDeviceAgeMonths = maxDeviceAgeMonths;
        this.minKycLevel        = minKycLevel;
    }

    /** Mark this offer withdrawn (no longer purchasable). */
    public void withdraw() { this.status = OfferStatus.WITHDRAWN; }

    /** (Re)publish this offer. */
    public void publish()  { this.status = OfferStatus.PUBLISHED; }

    public String      getOfferCode()         { return offerCode; }
    public String      getName()              { return name; }
    public String      getDescription()       { return description; }
    public String      getCategory()          { return category; }
    public long        getAmount()            { return amount; }
    public String      getCurrency()          { return currency; }
    public String      getPriceSnapshotId()   { return priceSnapshotId; }
    public OfferStatus getStatus()            { return status; }
    public PlanTier    getMinPlanTier()       { return minPlanTier; }
    public String      getAllowedRegions()    { return allowedRegions; }
    public Integer     getMaxDeviceAgeMonths(){ return maxDeviceAgeMonths; }
    public KycLevel    getMinKycLevel()       { return minKycLevel; }
}
