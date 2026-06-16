package com.vab.fulfilment.domain;

import com.vab.events.common.ProductType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FulfilmentRecordTest {

    @Test
    void new_record_is_fulfilled() {
        FulfilmentRecord r = new FulfilmentRecord("shp_1", "ord-1",
                ProductType.PHYSICAL_GOOD, "TRK1", null, null);
        assertThat(r.getStatus()).isEqualTo(FulfilmentRecord.Status.FULFILLED);
    }

    @Test
    void cancel_flips_to_cancelled() {
        FulfilmentRecord r = new FulfilmentRecord("shp_1", "ord-1",
                ProductType.PHYSICAL_GOOD, "TRK1", null, null);
        r.cancel();
        assertThat(r.getStatus()).isEqualTo(FulfilmentRecord.Status.CANCELLED);
    }
}
