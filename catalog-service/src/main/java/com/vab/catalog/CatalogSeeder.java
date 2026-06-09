package com.vab.catalog;

import com.vab.catalog.domain.KycLevel;
import com.vab.catalog.domain.Offer;
import com.vab.catalog.domain.OfferRepository;
import com.vab.catalog.domain.OfferStatus;
import com.vab.catalog.domain.PlanTier;
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

        offers.saveAll(List.of(
            new Offer("OTT_NETFLIX_6M", "Netflix 6-month bundle",
                    "Six months of Netflix on us.", "DIGITAL", 599, "INR",
                    "ps_2026_05_netflix6m", OfferStatus.PUBLISHED,
                    PlanTier.BASIC, "IN", null, KycLevel.MINIMAL),
            new Offer("OTT_PRIME_12M", "Prime Video 12-month bundle",
                    "A year of Prime Video.", "DIGITAL", 999, "INR",
                    "ps_2026_05_prime12m", OfferStatus.PUBLISHED,
                    PlanTier.PLUS, "IN", null, KycLevel.MINIMAL),
            new Offer("ACC_BUDS_PRO", "Wireless earbuds (Pro)",
                    "Premium ANC earbuds at a subsidised price.", "PHYSICAL", 1499, "INR",
                    "ps_2026_05_budspro", OfferStatus.PUBLISHED,
                    PlanTier.PREMIUM, "IN", 24, KycLevel.FULL),
            new Offer("REPAIR_PRIORITY_SLOT", "Priority repair slot",
                    "Skip the queue at a service centre.", "SLOT", 299, "INR",
                    "ps_2026_05_repairslot", OfferStatus.PUBLISHED,
                    PlanTier.BASIC, "IN", null, KycLevel.NONE),
            new Offer("OTT_LEGACY_3M", "Legacy 3-month bundle (withdrawn)",
                    "No longer offered.", "DIGITAL", 399, "INR",
                    "ps_2025_legacy3m", OfferStatus.WITHDRAWN,
                    PlanTier.BASIC, "IN", null, KycLevel.NONE)
        ));
        log.info("Seeded {} catalog offers into MongoDB", offers.count());
    }
}
