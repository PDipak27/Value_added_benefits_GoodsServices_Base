package com.vab.inventory.command;

import com.vab.inventory.domain.Reservation;
import com.vab.inventory.domain.ReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryReservationSweeperTest {

    @Mock ReservationRepository reservations;
    @Mock InventoryCommandHandlers handlers;

    @Test
    void releases_each_expired_reservation_by_id() {
        Reservation r1 = new Reservation("res-1", "OFF-p", 1, null, Instant.now());
        Reservation r2 = new Reservation("res-2", "OFF-p", 1, null, Instant.now());
        when(reservations.findByStatusAndReservedUntilBefore(eq(Reservation.Status.RESERVED), any(Instant.class)))
                .thenReturn(List.of(r1, r2));

        new InventoryReservationSweeper(reservations, handlers).sweepExpired();

        verify(handlers).releaseById("res-1");
        verify(handlers).releaseById("res-2");
    }

    @Test
    void does_nothing_when_no_reservations_expired() {
        when(reservations.findByStatusAndReservedUntilBefore(any(), any())).thenReturn(List.of());

        new InventoryReservationSweeper(reservations, handlers).sweepExpired();

        verifyNoInteractions(handlers);
    }
}
