package com.vab.inventory.command;

import com.vab.events.common.ProductType;
import com.vab.events.inventory.*;
import com.vab.inventory.domain.*;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Behaviour of the inventory saga participant. Repositories are mocked; we assert
 * reply outcome/type and the resulting holds on the {@link InventoryItem} /
 * {@link Reservation} / {@link LicenseKey} (captured via {@link ArgumentCaptor}).
 */
@ExtendWith(MockitoExtension.class)
class InventoryCommandHandlersTest {

    @Mock InventoryItemRepository items;
    @Mock ReservationRepository reservations;
    @Mock LicenseKeyRepository licenseKeys;

    private InventoryCommandHandlers handlers;

    @BeforeEach
    void setUp() {
        handlers = new InventoryCommandHandlers(items, reservations, licenseKeys);
    }

    @SuppressWarnings("unchecked")
    private static <C> CommandMessage<C> cmd(C command) {
        CommandMessage<C> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(command);
        return cm;
    }

    @Nested
    class Reserve {

        @Test
        void unknown_offer_fails_with_item_not_found() {
            when(items.findByOfferCodeForUpdate("OFF-x")).thenReturn(Optional.empty());

            Message reply = handlers.reserveInventory(cmd(new ReserveInventoryCommand("OFF-x", 1)));

            assertThat(Replies.assertFailure(reply, InventoryReservationFailed.class).getReason())
                    .isEqualTo("ITEM_NOT_FOUND");
        }

        @Test
        void physical_out_of_stock_fails_with_out_of_stock() {
            InventoryItem item = new InventoryItem("OFF-p", ProductType.PHYSICAL_GOOD, 1);
            item.reserve(1); // available now 0
            when(items.findByOfferCodeForUpdate("OFF-p")).thenReturn(Optional.of(item));

            Message reply = handlers.reserveInventory(cmd(new ReserveInventoryCommand("OFF-p", 1)));

            assertThat(Replies.assertFailure(reply, InventoryReservationFailed.class).getReason())
                    .isEqualTo("OUT_OF_STOCK");
        }

        @Test
        void physical_success_reserves_item_and_persists_reservation() {
            InventoryItem item = new InventoryItem("OFF-p", ProductType.PHYSICAL_GOOD, 5);
            when(items.findByOfferCodeForUpdate("OFF-p")).thenReturn(Optional.of(item));

            Message reply = handlers.reserveInventory(cmd(new ReserveInventoryCommand("OFF-p", 2)));

            InventoryReserved body = Replies.assertSuccess(reply, InventoryReserved.class);
            assertThat(body.getProductType()).isEqualTo("PHYSICAL_GOOD");
            assertThat(body.getActivationKey()).isNull(); // physical => no license key
            assertThat(item.getReserved()).isEqualTo(2);
            verify(items).save(item);

            ArgumentCaptor<Reservation> res = ArgumentCaptor.forClass(Reservation.class);
            verify(reservations).save(res.capture());
            assertThat(res.getValue().isReserved()).isTrue();
            assertThat(res.getValue().getReservedUntil()).isNotNull(); // TTL stamped
        }

        @Test
        void license_success_claims_a_free_key() {
            InventoryItem item = new InventoryItem("OFF-l", ProductType.SOFTWARE_LICENSE, 5);
            LicenseKey free = new LicenseKey("KEY-1", "OFF-l");
            when(items.findByOfferCodeForUpdate("OFF-l")).thenReturn(Optional.of(item));
            when(licenseKeys.findByOfferCodeAndStatusForUpdate(eq("OFF-l"), eq(LicenseKey.Status.FREE), any(Pageable.class)))
                    .thenReturn(List.of(free));

            Message reply = handlers.reserveInventory(cmd(new ReserveInventoryCommand("OFF-l", 1)));

            InventoryReserved body = Replies.assertSuccess(reply, InventoryReserved.class);
            assertThat(body.getActivationKey()).isEqualTo("KEY-1");
            assertThat(free.getStatus()).isEqualTo(LicenseKey.Status.ALLOCATED);
        }

        @Test
        void license_with_no_free_keys_fails_with_pool_exhausted() {
            InventoryItem item = new InventoryItem("OFF-l", ProductType.SOFTWARE_LICENSE, 5);
            when(items.findByOfferCodeForUpdate("OFF-l")).thenReturn(Optional.of(item));
            when(licenseKeys.findByOfferCodeAndStatusForUpdate(eq("OFF-l"), eq(LicenseKey.Status.FREE), any(Pageable.class)))
                    .thenReturn(List.of());

            Message reply = handlers.reserveInventory(cmd(new ReserveInventoryCommand("OFF-l", 1)));

            assertThat(Replies.assertFailure(reply, InventoryReservationFailed.class).getReason())
                    .isEqualTo("POOL_EXHAUSTED");
        }
    }

    @Nested
    class Commit {

        @Test
        void unknown_reservation_fails() {
            when(reservations.findById("res-x")).thenReturn(Optional.empty());

            Message reply = handlers.commitInventory(cmd(new CommitInventoryCommand("res-x")));

            assertThat(Replies.assertFailure(reply, InventoryCommitFailed.class).getReason())
                    .isEqualTo("RESERVATION_NOT_FOUND");
        }

        @Test
        void already_allocated_is_idempotent_success() {
            when(reservations.findById("res-1")).thenReturn(Optional.of(
                    Reservation.allocated("res-1", "OFF-p", 1, null)));

            Message reply = handlers.commitInventory(cmd(new CommitInventoryCommand("res-1")));

            Replies.assertSuccess(reply, InventoryCommitted.class);
            verify(items, never()).save(any()); // no double-commit
        }

        @Test
        void released_reservation_cannot_commit() {
            Reservation res = new Reservation("res-1", "OFF-p", 1, null, java.time.Instant.now());
            res.markReleased();
            when(reservations.findById("res-1")).thenReturn(Optional.of(res));

            Message reply = handlers.commitInventory(cmd(new CommitInventoryCommand("res-1")));

            assertThat(Replies.assertFailure(reply, InventoryCommitFailed.class).getReason())
                    .isEqualTo("RESERVATION_RELEASED");
        }

        @Test
        void reserved_commits_item_and_marks_allocated() {
            InventoryItem item = new InventoryItem("OFF-p", ProductType.PHYSICAL_GOOD, 5);
            item.reserve(2);
            Reservation res = new Reservation("res-1", "OFF-p", 2, null, java.time.Instant.now());
            when(reservations.findById("res-1")).thenReturn(Optional.of(res));
            when(items.findByOfferCodeForUpdate("OFF-p")).thenReturn(Optional.of(item));

            Message reply = handlers.commitInventory(cmd(new CommitInventoryCommand("res-1")));

            Replies.assertSuccess(reply, InventoryCommitted.class);
            assertThat(res.isAllocated()).isTrue();
            assertThat(item.getReserved()).isZero();
            assertThat(item.getAllocated()).isEqualTo(2); // reserved -> allocated
        }
    }

    @Nested
    class Allocate {

        @Test
        void firm_one_step_allocation_persists_allocated_reservation() {
            InventoryItem item = new InventoryItem("OFF-p", ProductType.PHYSICAL_GOOD, 5);
            when(items.findByOfferCodeForUpdate("OFF-p")).thenReturn(Optional.of(item));

            Message reply = handlers.allocateInventory(cmd(new AllocateInventoryCommand("OFF-p", 3)));

            Replies.assertSuccess(reply, InventoryAllocated.class);
            assertThat(item.getAllocated()).isEqualTo(3);
            ArgumentCaptor<Reservation> res = ArgumentCaptor.forClass(Reservation.class);
            verify(reservations).save(res.capture());
            assertThat(res.getValue().isAllocated()).isTrue();
            assertThat(res.getValue().getReservedUntil()).isNull(); // firm hold => no expiry
        }
    }

    @Nested
    class Release {

        @Test
        void reserved_release_returns_hold_frees_key_and_marks_released() {
            InventoryItem item = new InventoryItem("OFF-l", ProductType.SOFTWARE_LICENSE, 5);
            item.reserve(1);
            Reservation res = new Reservation("res-1", "OFF-l", 1, "KEY-1", java.time.Instant.now());
            LicenseKey key = new LicenseKey("KEY-1", "OFF-l");
            key.allocate("res-1");
            when(reservations.findById("res-1")).thenReturn(Optional.of(res));
            when(items.findByOfferCodeForUpdate("OFF-l")).thenReturn(Optional.of(item));
            when(licenseKeys.findById("KEY-1")).thenReturn(Optional.of(key));

            Message reply = handlers.releaseInventory(cmd(new ReleaseInventoryCommand("res-1")));

            Replies.assertSuccessOutcome(reply);
            assertThat(item.getReserved()).isZero();
            assertThat(key.getStatus()).isEqualTo(LicenseKey.Status.FREE);
            assertThat(res.isReleased()).isTrue();
        }

        @Test
        void unknown_reservation_release_is_a_no_op_success() {
            when(reservations.findById("res-x")).thenReturn(Optional.empty());

            Message reply = handlers.releaseInventory(cmd(new ReleaseInventoryCommand("res-x")));

            Replies.assertSuccessOutcome(reply);
            verify(items, never()).save(any());
        }

        @Test
        void already_released_reservation_is_a_no_op() {
            Reservation res = new Reservation("res-1", "OFF-p", 1, null, java.time.Instant.now());
            res.markReleased();
            when(reservations.findById("res-1")).thenReturn(Optional.of(res));

            handlers.releaseById("res-1");

            verify(items, never()).findByOfferCodeForUpdate(any());
            verify(reservations, never()).save(any());
        }
    }
}
