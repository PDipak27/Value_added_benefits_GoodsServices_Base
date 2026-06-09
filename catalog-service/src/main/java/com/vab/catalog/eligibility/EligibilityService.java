package com.vab.catalog.eligibility;

import com.vab.catalog.domain.Offer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Evaluates an {@link Offer}'s eligibility constraints against a
 * {@link SubscriberProfile}. Each dimension is reported as a {@link RuleResult}
 * so callers can explain a rejection. Unconstrained offer fields and unknown
 * (null) profile fields pass by default.
 */
@Service
public class EligibilityService {

    public EligibilityResult evaluate(Offer offer, SubscriberProfile p) {
        List<RuleResult> rules = new ArrayList<>();

        // Plan tier
        boolean planOk = offer.getMinPlanTier() == null
                || p.planTier() == null
                || p.planTier().ordinal() >= offer.getMinPlanTier().ordinal();
        rules.add(new RuleResult("PLAN_TIER", planOk,
                planOk ? "ok" : "requires plan tier >= " + offer.getMinPlanTier()));

        // Region
        boolean regionOk = isBlank(offer.getAllowedRegions())
                || p.region() == null
                || allowedRegions(offer).contains(p.region());
        rules.add(new RuleResult("REGION", regionOk,
                regionOk ? "ok" : "not available in region " + p.region()));

        // Device age
        boolean deviceOk = offer.getMaxDeviceAgeMonths() == null
                || p.deviceAgeMonths() == null
                || p.deviceAgeMonths() <= offer.getMaxDeviceAgeMonths();
        rules.add(new RuleResult("DEVICE_AGE", deviceOk,
                deviceOk ? "ok" : "requires device age <= " + offer.getMaxDeviceAgeMonths() + " months"));

        // KYC
        boolean kycOk = offer.getMinKycLevel() == null
                || p.kycLevel() == null
                || p.kycLevel().ordinal() >= offer.getMinKycLevel().ordinal();
        rules.add(new RuleResult("KYC", kycOk,
                kycOk ? "ok" : "requires KYC level >= " + offer.getMinKycLevel()));

        boolean eligible = rules.stream().allMatch(RuleResult::passed);
        return new EligibilityResult(offer.getOfferCode(), eligible, rules);
    }

    public boolean isEligible(Offer offer, SubscriberProfile profile) {
        return evaluate(offer, profile).eligible();
    }

    private static List<String> allowedRegions(Offer offer) {
        return Arrays.stream(offer.getAllowedRegions().split(","))
                .map(String::trim)
                .toList();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
