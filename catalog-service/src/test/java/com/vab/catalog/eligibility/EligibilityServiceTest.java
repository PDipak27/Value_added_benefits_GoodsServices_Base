package com.vab.catalog.eligibility;

import com.vab.catalog.domain.KycLevel;
import com.vab.catalog.domain.Offer;
import com.vab.catalog.domain.OfferStatus;
import com.vab.catalog.domain.PlanTier;
import com.vab.events.common.ProductType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the eligibility rule engine. No mocking needed — the
 * service has no collaborators; it evaluates an {@link Offer} against a
 * {@link SubscriberProfile} and reports a {@link RuleResult} per dimension.
 */
class EligibilityServiceTest {

    private final EligibilityService service = new EligibilityService();

    /** Offer constrained on every dimension — the "fully restricted" baseline. */
    private static Offer offer(PlanTier minPlanTier, String allowedRegions,
                               Integer maxDeviceAgeMonths, KycLevel minKycLevel) {
        return new Offer("OFF-1", "Test", "desc", ProductType.DIGITAL_SUBSCRIPTION,
                1000, "INR", "px-1", OfferStatus.PUBLISHED,
                minPlanTier, allowedRegions, maxDeviceAgeMonths, minKycLevel);
    }

    private RuleResult rule(EligibilityResult result, String name) {
        return result.rules().stream().filter(r -> r.rule().equals(name)).findFirst().orElseThrow();
    }

    @Nested
    class PlanTier_ {

        @Test
        void passes_when_profile_tier_meets_or_exceeds_minimum() {
            Offer o = offer(PlanTier.PLUS, null, null, null);
            SubscriberProfile p = new SubscriberProfile(PlanTier.PREMIUM, null, null, null);

            EligibilityResult result = service.evaluate(o, p);

            assertThat(rule(result, "PLAN_TIER").passed()).isTrue();
            assertThat(result.eligible()).isTrue();
        }

        @Test
        void fails_when_profile_tier_below_minimum() {
            Offer o = offer(PlanTier.PREMIUM, null, null, null);
            SubscriberProfile p = new SubscriberProfile(PlanTier.BASIC, null, null, null);

            EligibilityResult result = service.evaluate(o, p);

            assertThat(rule(result, "PLAN_TIER").passed()).isFalse();
            assertThat(result.eligible()).isFalse();
        }

        @Test
        void passes_when_offer_unconstrained_or_profile_field_unknown() {
            assertThat(service.isEligible(offer(null, null, null, null),
                    new SubscriberProfile(PlanTier.BASIC, null, null, null))).isTrue();
            // Null profile tier => "unknown" => passes even against a constrained offer.
            assertThat(service.isEligible(offer(PlanTier.PREMIUM, null, null, null),
                    new SubscriberProfile(null, null, null, null))).isTrue();
        }
    }

    @Nested
    class Region {

        @Test
        void passes_when_profile_region_in_comma_separated_allowed_list() {
            Offer o = offer(null, "IN, US , SG", null, null);
            // Whitespace around tokens is trimmed before matching.
            assertThat(service.isEligible(o, new SubscriberProfile(null, "US", null, null))).isTrue();
        }

        @Test
        void fails_when_profile_region_not_in_allowed_list() {
            Offer o = offer(null, "IN,US", null, null);
            EligibilityResult result = service.evaluate(o, new SubscriberProfile(null, "SG", null, null));
            assertThat(rule(result, "REGION").passed()).isFalse();
        }

        @Test
        void passes_when_allowed_regions_blank_or_profile_region_null() {
            assertThat(service.isEligible(offer(null, "  ", null, null),
                    new SubscriberProfile(null, "SG", null, null))).isTrue();
            assertThat(service.isEligible(offer(null, "IN", null, null),
                    new SubscriberProfile(null, null, null, null))).isTrue();
        }
    }

    @Nested
    class DeviceAge {

        @Test
        void passes_when_device_age_at_or_below_max() {
            assertThat(service.isEligible(offer(null, null, 12, null),
                    new SubscriberProfile(null, null, 12, null))).isTrue();
        }

        @Test
        void fails_when_device_older_than_max() {
            EligibilityResult result = service.evaluate(offer(null, null, 12, null),
                    new SubscriberProfile(null, null, 13, null));
            assertThat(rule(result, "DEVICE_AGE").passed()).isFalse();
        }
    }

    @Nested
    class Kyc {

        @Test
        void passes_when_kyc_meets_or_exceeds_minimum() {
            assertThat(service.isEligible(offer(null, null, null, KycLevel.MINIMAL),
                    new SubscriberProfile(null, null, null, KycLevel.FULL))).isTrue();
        }

        @Test
        void fails_when_kyc_below_minimum() {
            EligibilityResult result = service.evaluate(offer(null, null, null, KycLevel.FULL),
                    new SubscriberProfile(null, null, null, KycLevel.NONE));
            assertThat(rule(result, "KYC").passed()).isFalse();
        }
    }

    @Test
    void eligible_only_when_every_rule_passes() {
        Offer o = offer(PlanTier.PLUS, "IN", 24, KycLevel.MINIMAL);
        // Satisfies plan/region/device but fails KYC => overall ineligible.
        EligibilityResult result = service.evaluate(o,
                new SubscriberProfile(PlanTier.PREMIUM, "IN", 10, KycLevel.NONE));

        assertThat(result.eligible()).isFalse();
        assertThat(result.rules()).hasSize(4);
        assertThat(result.offerCode()).isEqualTo("OFF-1");
    }

    @Test
    void eligible_when_all_four_constraints_satisfied() {
        Offer o = offer(PlanTier.PLUS, "IN", 24, KycLevel.MINIMAL);
        assertThat(service.isEligible(o,
                new SubscriberProfile(PlanTier.PREMIUM, "IN", 10, KycLevel.FULL))).isTrue();
    }
}
