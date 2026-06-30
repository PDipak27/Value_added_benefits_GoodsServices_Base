package com.vab.e2e;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Saga failure paths that end the order FAILED (DD-26): reserve miss
 * (ITEM_NOT_FOUND for an offer with no inventory row), authorize decline
 * (amount &gt; 999), and capture hard-decline at the pivot (magic amount 777,
 * which rolls back the prior holds — nothing captured, no refund).
 */
class OrderFailurePathsE2E extends E2EBase {
	
	
    @Test
    void reserve_item_not_found_fails() {
        // No inventory row for this offerCode → reserve replies ITEM_NOT_FOUND.
        String orderId = placeOrder(sub(), "NOPE_NO_INVENTORY", "PHYSICAL_GOOD", 499, "PAY_NOW");
        awaitStatus(orderId, "FAILED");
    }

    @Test
    void authorize_decline_over_threshold_fails() {
        // amount > 999 → billing authorize declines (AMOUNT_EXCEEDS_LIMIT).
        String orderId = placeOrder(sub(), "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 1500, "PAY_NOW");
        awaitStatus(orderId, "FAILED");
    }

    @Test
    void capture_hard_decline_at_pivot_fails() {
        // amount == 777 authorizes (≤ 999) but hard-declines at capture (the pivot).
        String orderId = placeOrder(sub(), "OTT_HOTSTAR_3M", "DIGITAL_SUBSCRIPTION", 777, "PAY_NOW");
        awaitStatus(orderId, "FAILED");
    }
}
