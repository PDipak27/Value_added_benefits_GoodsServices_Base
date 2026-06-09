package com.vab.catalog.eligibility;

import com.vab.catalog.domain.KycLevel;
import com.vab.catalog.domain.PlanTier;

/**
 * The subscriber attributes eligibility is evaluated against. In production this
 * is derived from the subscriber JWT / profile service; here it is supplied by
 * the caller (query params or request body). Null fields are treated as
 * "unknown" and do not fail their rule.
 */
public record SubscriberProfile(
        PlanTier planTier,
        String   region,
        Integer  deviceAgeMonths,
        KycLevel kycLevel) {
}
