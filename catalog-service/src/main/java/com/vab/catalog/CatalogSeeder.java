package com.vab.catalog;

import com.vab.catalog.domain.KycLevel;
import com.vab.catalog.domain.Offer;
import com.vab.catalog.domain.OfferRepository;
import com.vab.catalog.domain.OfferStatus;
import com.vab.catalog.domain.PlanTier;
import com.vab.events.common.ProductType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the walking-skeleton catalog into MongoDB on startup when the collection
 * is empty (DD-16) — replaces the former Flyway-seeded {@code catalog.offers}
 * table. Idempotent: a non-empty collection is left untouched.
 */
@Component
public class CatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);

    private final OfferRepository offers;

    public CatalogSeeder(OfferRepository offers) {
        this.offers = offers;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (offers.count() > 0) {
            log.debug("Catalog already seeded ({} offers) — skipping", offers.count());
            return;
        }

        // Offers across the three product types (Design/09). All three FINITE types
        // align 1:1 with the inventory seed: PHYSICAL_GOOD → stock count,
        // SOFTWARE_LICENSE → key pool, DIGITAL_SUBSCRIPTION → entitlement count
        // (made finite in inventory V4). OTT_LEGACY_3M is withdrawn and has no
        // inventory row (exercises the withdrawn/ITEM_NOT_FOUND paths).
        offers.saveAll(List.of(
            // ── DIGITAL_SUBSCRIPTION (finite entitlement count; inventory V4/V5) ─
            new Offer("OTT_NETFLIX_6M", "Netflix 6-month bundle",
                    "Six months of Netflix on us.", ProductType.DIGITAL_SUBSCRIPTION, 599, "INR",
                    "ps_2026_05_netflix6m", OfferStatus.PUBLISHED,
                    PlanTier.BASIC, "IN", null, KycLevel.MINIMAL),
            new Offer("OTT_PRIME_12M", "Prime Video 12-month bundle",
                    "A year of Prime Video.", ProductType.DIGITAL_SUBSCRIPTION, 999, "INR",
                    "ps_2026_05_prime12m", OfferStatus.PUBLISHED,
                    PlanTier.PLUS, "IN", null, KycLevel.MINIMAL),
            new Offer("OTT_HOTSTAR_3M", "Hotstar 3-month bundle",
                    "Three months of Hotstar.", ProductType.DIGITAL_SUBSCRIPTION, 499, "INR",
                    "ps_2026_05_hotstar3m", OfferStatus.PUBLISHED,
                    PlanTier.BASIC, "IN", null, KycLevel.MINIMAL),
            // OTT provisioning-failure demo triggers (DD-27). The offerCode is
            // forwarded to ott-service, which keys its 503/422 triggers off it:
            // OTTDOWN → 503 (retry then park), OTTBAD → 422 (park, no retry). Both
            // park the order in FULFILMENT_FAILED. Inventory rows: V5 migration.
            new Offer("OTT_OTTDOWN_1M", "OTT (provider-down demo)",
                    "Provisioning fails with 503 to exercise the FULFILMENT_FAILED park (DD-27).",
                    ProductType.DIGITAL_SUBSCRIPTION, 499, "INR",
                    "ps_2026_05_ottdown1m", OfferStatus.PUBLISHED,
                    PlanTier.BASIC, "IN", null, KycLevel.MINIMAL),
            new Offer("OTT_OTTBAD_1M", "OTT (provider-reject demo)",
                    "Provisioning fails with 422 to exercise the FULFILMENT_FAILED park (DD-27).",
                    ProductType.DIGITAL_SUBSCRIPTION, 499, "INR",
                    "ps_2026_05_ottbad1m", OfferStatus.PUBLISHED,
                    PlanTier.BASIC, "IN", null, KycLevel.MINIMAL),
            // ── SOFTWARE_LICENSE (finite key pool) ───────────────────────────
            new Offer("SW_MSOFFICE_1Y", "Microsoft 365 Personal (1 year)",
                    "One-year Microsoft 365 activation key.", ProductType.SOFTWARE_LICENSE, 899, "INR",
                    "ps_2026_05_msoffice1y", OfferStatus.PUBLISHED,
                    PlanTier.BASIC, "IN", null, KycLevel.MINIMAL),
            new Offer("SW_ANTIVIRUS_1Y", "NortonLife antivirus (1 year)",
                    "One-year antivirus activation key (limited keys).", ProductType.SOFTWARE_LICENSE, 499, "INR",
                    "ps_2026_05_antivirus1y", OfferStatus.PUBLISHED,
                    PlanTier.BASIC, "IN", null, KycLevel.MINIMAL),
            // ── PHYSICAL_GOOD (finite stock count) ───────────────────────────
            new Offer("ACC_BUDS_PRO", "Wireless earbuds (Pro)",
                    "Premium ANC earbuds at a subsidised price.", ProductType.PHYSICAL_GOOD, 1499, "INR",
                    "ps_2026_05_budspro", OfferStatus.PUBLISHED,
                    PlanTier.PREMIUM, "IN", 24, KycLevel.FULL),
            new Offer("ACC_POWERBANK_20K", "20K power bank",
                    "20,000 mAh fast-charge power bank (limited stock).", ProductType.PHYSICAL_GOOD, 899, "INR",
                    "ps_2026_05_powerbank20k", OfferStatus.PUBLISHED,
                    PlanTier.BASIC, "IN", null, KycLevel.MINIMAL),
            // ── Withdrawn (no inventory) ─────────────────────────────────────
            new Offer("OTT_LEGACY_3M", "Legacy 3-month bundle (withdrawn)",
                    "No longer offered.", ProductType.DIGITAL_SUBSCRIPTION, 399, "INR",
                    "ps_2025_legacy3m", OfferStatus.WITHDRAWN,
                    PlanTier.BASIC, "IN", null, KycLevel.NONE)
        ));
        log.info("Seeded {} catalog offers into MongoDB", offers.count());
    }
}
