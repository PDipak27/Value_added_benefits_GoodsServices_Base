package com.vab.catalog.api;

import com.vab.catalog.domain.KycLevel;
import com.vab.catalog.domain.Offer;
import com.vab.catalog.domain.OfferRepository;
import com.vab.catalog.domain.OfferStatus;
import com.vab.catalog.domain.PlanTier;
import com.vab.catalog.eligibility.EligibilityResult;
import com.vab.catalog.eligibility.EligibilityService;
import com.vab.catalog.eligibility.SubscriberProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Catalog & Eligibility REST surface (see Design/05-api-contracts.md).
 *
 * <p>In production the subscriber profile comes from the JWT; here it is accepted
 * as query params (list) or a request body (evaluate) so the service is testable
 * before the gateway/OIDC stack exists.
 */
@RestController
@RequestMapping("/v1/offers")
public class OfferController {

    private static final Logger log = LoggerFactory.getLogger(OfferController.class);

    private final OfferRepository    offers;
    private final EligibilityService eligibility;

    public OfferController(OfferRepository offers, EligibilityService eligibility) {
        this.offers      = offers;
        this.eligibility = eligibility;
    }

    /** Eligibility-filtered list of published offers for the caller's profile. */
    @GetMapping
    public List<OfferSummary> list(
            @RequestParam(required = false) PlanTier planTier,
            @RequestParam(required = false) String   region,
            @RequestParam(required = false) Integer  deviceAgeMonths,
            @RequestParam(required = false) KycLevel kycLevel) {

        SubscriberProfile profile = new SubscriberProfile(planTier, region, deviceAgeMonths, kycLevel);
        List<OfferSummary> result = offers.findByStatus(OfferStatus.PUBLISHED).stream()
                .filter(o -> eligibility.isEligible(o, profile))
                .map(OfferSummary::from)
                .toList();
        log.debug("Listed {} eligible offers for profile={}", result.size(), profile);
        return result;
    }

    /** Offer detail incl. priceSnapshotId. 404 if absent or withdrawn. */
    @GetMapping("/{offerCode}")
    public ResponseEntity<Offer> detail(@PathVariable String offerCode) {
        return offers.findById(offerCode)
                .filter(o -> o.getStatus() == OfferStatus.PUBLISHED)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Rule-level eligibility result — "why can't I buy this?". 404 if absent. */
    @PostMapping("/{offerCode}:evaluate")
    public ResponseEntity<EligibilityResult> evaluate(
            @PathVariable String offerCode,
            @RequestBody SubscriberProfile profile) {

        return offers.findById(offerCode)
                .map(o -> ResponseEntity.ok(eligibility.evaluate(o, profile)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
