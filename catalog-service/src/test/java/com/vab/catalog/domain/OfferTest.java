package com.vab.catalog.domain;

import com.vab.events.common.ProductType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfferTest {

    private static Offer offer(OfferStatus status) {
        return new Offer("OFF-1", "Test", "desc", ProductType.DIGITAL_SUBSCRIPTION,
                1000, "INR", "px-1", status, null, null, null, null);
    }

    @Test
    void withdraw_then_publish_toggles_status() {
        Offer o = offer(OfferStatus.PUBLISHED);

        o.withdraw();
        assertThat(o.getStatus()).isEqualTo(OfferStatus.WITHDRAWN);

        o.publish();
        assertThat(o.getStatus()).isEqualTo(OfferStatus.PUBLISHED);
    }
}
