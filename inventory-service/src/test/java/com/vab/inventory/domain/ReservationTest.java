package com.vab.inventory.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationTest {

    @Test
    void reserve_ctor_starts_reserved_with_expiry() {
        Instant until = Instant.now().plusSeconds(600);
        Reservation r = new Reservation("res-1", "OFF-p", 1, null, until);
        assertThat(r.isReserved()).isTrue();
        assertThat(r.getReservedUntil()).isEqualTo(until);
    }

    @Test
    void allocated_factory_starts_allocated_without_expiry() {
        Reservation r = Reservation.allocated("res-1", "OFF-p", 1, "KEY-1");
        assertThat(r.isAllocated()).isTrue();
        assertThat(r.getReservedUntil()).isNull();
        assertThat(r.getLicenseKey()).isEqualTo("KEY-1");
    }

    @Test
    void markAllocated_clears_expiry_and_markReleased_sets_released() {
        Reservation r = new Reservation("res-1", "OFF-p", 1, null, Instant.now());
        r.markAllocated();
        assertThat(r.isAllocated()).isTrue();
        assertThat(r.getReservedUntil()).isNull();

        r.markReleased();
        assertThat(r.isReleased()).isTrue();
    }
}
