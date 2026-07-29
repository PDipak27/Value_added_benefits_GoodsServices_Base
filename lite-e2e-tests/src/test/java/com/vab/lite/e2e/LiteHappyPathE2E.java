package com.vab.lite.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.restassured.path.json.JsonPath;

/**
 * LITE happy-path: place a PAY_NOW order and drive the saga to COMPLETED.
 *
 * <p>Exercises the full Lite saga — reserve → authorize → commit → CAPTURE (pivot)
 * → confirm → fulfil (local stub) → COMPLETED — across api-gateway, lite-order,
 * lite-inventory and lite-billing over Kafka + the eventuate-cdc outbox relay.
 *
 * <p>Inputs use the seeded fixtures: subscriber {@code sub-premium} (ACTIVE,
 * credit_limit 5000) and offer {@code OTT_NETFLIX_6M} (DIGITAL_SUBSCRIPTION, stock 100).
 */
class LiteHappyPathE2E extends LiteE2EBase {

    @Test
    @DisplayName("PAY_NOW order reaches COMPLETED with a delivery ref")
    void payNowOrderCompletes() {
        String orderId = placeOrder(
                "sub-premium",
                "OTT_NETFLIX_6M",
                "DIGITAL_SUBSCRIPTION",
                999L,
                "PAY_NOW");

        awaitStatus(orderId, "COMPLETED");

        JsonPath order = getOrder(orderId);
        assertThat(order.getString("orderId")).isEqualTo(orderId);
        assertThat(order.getString("subscriberId")).isEqualTo("sub-premium");
        assertThat(order.getString("externalRef")).isEqualTo("lite-" + orderId);
    }
}
