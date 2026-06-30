package com.vab.catalog.domain;

import com.vab.events.common.ProductType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An offer in the catalog, with its current price snapshot and the eligibility
 * constraints a subscriber must satisfy to purchase it (DD-16).
 *
 * <p>Stored as a MongoDB document. Offers are polymorphic across product types
 * (PHYSICAL_GOOD / DIGITAL_SUBSCRIPTION / SOFTWARE_LICENSE) and their eligibility
 * dimensions evolve often, so a document model lets the shape change without
 * per-attribute schema migrations. Enums are persisted as their string names by
 * the default Mongo mapping. Catalog is the <em>authoritative</em> source of an
 * offer's {@link ProductType} (Design/09).
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

    /** The product type this offer is — drives inventory, fulfilment and display. */
    private ProductType productType;

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

    /**
     * Benefit term in months for DIGITAL_SUBSCRIPTION / SOFTWARE_LICENSE
     * (null = no term / perpetual / PHYSICAL_GOOD). Orders snapshot it at
     * placement; fulfilment turns it into the entitlement's validUntil.
     */
    private Integer termMonths;

    protected Offer() {}  // Mongo mapping

    public Offer(String offerCode, String name, String description, ProductType productType,
                 long amount, String currency, String priceSnapshotId, OfferStatus status,
                 PlanTier minPlanTier, String allowedRegions, Integer maxDeviceAgeMonths,
                 KycLevel minKycLevel) {
        this.offerCode          = offerCode;
        this.name               = name;
        this.description        = description;
        this.productType        = productType;
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
    public ProductType getProductType()       { return productType; }
    public long        getAmount()            { return amount; }
    public String      getCurrency()          { return currency; }
    public String      getPriceSnapshotId()   { return priceSnapshotId; }
    public OfferStatus getStatus()            { return status; }
    public PlanTier    getMinPlanTier()       { return minPlanTier; }
    public String      getAllowedRegions()    { return allowedRegions; }
    public Integer     getMaxDeviceAgeMonths(){ return maxDeviceAgeMonths; }
    public KycLevel    getMinKycLevel()       { return minKycLevel; }
    public Integer     getTermMonths()        { return termMonths; }

    /** Set the benefit term (DIGITAL_SUBSCRIPTION / SOFTWARE_LICENSE). */
    public Offer withTermMonths(Integer termMonths) { this.termMonths = termMonths; return this; }
}
