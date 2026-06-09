package com.vab.catalog.eligibility;

import java.util.List;

public record EligibilityResult(String offerCode, boolean eligible, List<RuleResult> rules) {
}
