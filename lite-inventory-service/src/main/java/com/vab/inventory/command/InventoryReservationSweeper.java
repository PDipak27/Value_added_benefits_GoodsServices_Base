package com.vab.inventory.command;

import com.vab.inventory.domain.Reservation;
import com.vab.inventory.domain.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Auto-releases PAY_NOW temporary holds whose {@code reservedUntil} has passed
 * (payment never committed). Runs periodically; each reservation is released in
 * its own transaction via {@link InventoryCommandHandlers#releaseById(String)},
 * which is idempotent and races safely with a late commit/release.
 */
@Component
public class InventoryReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationSweeper.class);

    private final ReservationRepository    reservations;
    private final InventoryCommandHandlers handlers;

    public InventoryReservationSweeper(ReservationRepository reservations,
                                       InventoryCommandHandlers handlers) {
        this.reservations = reservations;
        this.handlers     = handlers;
    }

    @Scheduled(fixedDelayString = "${inventory.reservation-sweep-ms:60000}")
    public void sweepExpired() {
        List<Reservation> expired =
                reservations.findByStatusAndReservedUntilBefore(Reservation.Status.RESERVED, Instant.now());
        if (expired.isEmpty()) return;
        log.info("Sweeping {} expired reservation(s)", expired.size());
        for (Reservation r : expired) {
            handlers.releaseById(r.getReservationId());
        }
    }
}
