package com.vab.catalog.eligibility;

/** Outcome of a single eligibility rule — supports "why can't I buy this?". */
public record RuleResult(String rule, boolean passed, String detail) {
}
