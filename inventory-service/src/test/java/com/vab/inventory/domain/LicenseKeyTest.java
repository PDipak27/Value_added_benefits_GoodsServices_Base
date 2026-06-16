package com.vab.inventory.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseKeyTest {

    @Test
    void new_key_is_free() {
        LicenseKey k = new LicenseKey("KEY-1", "OFF-l");
        assertThat(k.getStatus()).isEqualTo(LicenseKey.Status.FREE);
        assertThat(k.getReservationId()).isNull();
    }

    @Test
    void allocate_then_free_round_trips_status_and_reservation() {
        LicenseKey k = new LicenseKey("KEY-1", "OFF-l");

        k.allocate("res-1");
        assertThat(k.getStatus()).isEqualTo(LicenseKey.Status.ALLOCATED);
        assertThat(k.getReservationId()).isEqualTo("res-1");

        k.free();
        assertThat(k.getStatus()).isEqualTo(LicenseKey.Status.FREE);
        assertThat(k.getReservationId()).isNull();
    }
}
