package com.vab.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, String> {

    /** Expired temporary holds the sweeper should auto-release. */
    List<Reservation> findByStatusAndReservedUntilBefore(Reservation.Status status, Instant cutoff);
}
