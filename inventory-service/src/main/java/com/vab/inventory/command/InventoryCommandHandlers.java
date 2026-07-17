package com.vab.inventory.command;

import com.vab.events.common.ProductType;
import com.vab.events.inventory.*;
import com.vab.inventory.domain.InventoryItem;
import com.vab.inventory.domain.InventoryItemRepository;
import com.vab.inventory.domain.LicenseKey;
import com.vab.inventory.domain.LicenseKeyRepository;
import com.vab.inventory.domain.Reservation;
import com.vab.inventory.domain.ReservationRepository;
import io.eventuate.tram.commands.consumer.CommandHandlers;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.sagas.participant.SagaCommandHandlersBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withFailure;
import static io.eventuate.tram.commands.consumer.CommandHandlerReplyBuilder.withSuccess;

/**
 * Saga participant on the "inventoryService" channel. All three {@link ProductType}s
 * are finite now (DD-23): each has an inventory row. Two grant paths exist —
 * {@code reserve} (temporary hold, PAY_NOW) then {@code commit} (→ allocated), and
 * one-step {@code allocate} (BILL_TO_MOBILE). {@code release} returns whichever
 * hold a reservation held. SOFTWARE_LICENSE additionally claims a concrete key.
 *
 * <p>Idempotency: Tram {@code received_messages} dedups redelivery; commit/release
 * are additionally idempotent via the reservation {@code status}.
 */
@Component
public class InventoryCommandHandlers {

    private static final Logger log = LoggerFactory.getLogger(InventoryCommandHandlers.class);

    /** PAY_NOW temporary-hold lifetime; the sweeper auto-releases past it. */
    static final Duration RESERVATION_TTL = Duration.ofMinutes(10);

    private final InventoryItemRepository items;
    private final ReservationRepository   reservations;
    private final LicenseKeyRepository    licenseKeys;

    public InventoryCommandHandlers(InventoryItemRepository items,
                                    ReservationRepository reservations,
                                    LicenseKeyRepository licenseKeys) {
        this.items        = items;
        this.reservations = reservations;
        this.licenseKeys  = licenseKeys;
    }

    public CommandHandlers commandHandlerDefinitions() {
        return SagaCommandHandlersBuilder
                .fromChannel("inventoryService")
                .onMessage(ReserveInventoryCommand.class,  this::reserveInventory)
                .onMessage(CommitInventoryCommand.class,   this::commitInventory)
                .onMessage(AllocateInventoryCommand.class, this::allocateInventory)
                .onMessage(ReleaseInventoryCommand.class,  this::releaseInventory)
                .build();
    }

    // ── Reserve (temporary hold, PAY_NOW) ────────────────────────────────────

    @Transactional
    public Message reserveInventory(CommandMessage<ReserveInventoryCommand> cm) {
        // §C2 TRACE-DIAG (temporary): did the trace context arrive on the message, and is an
        // OTel span actually active on this handler thread? (Blank traceId in logs = no active span.)
        Message raw = cm.getMessage();
        SpanContext sc = Span.current().getSpanContext();
        log.info("TRACE-DIAG reserveInventory | headers={} | otelSpanValid={} otelTraceId={} otelSpanId={} | ctx={}",
                raw.getHeaders(), sc.isValid(), sc.getTraceId(), sc.getSpanId(), Context.current());

        ReserveInventoryCommand cmd = cm.getCommand();
        InventoryItem item = items.findByOfferCodeForUpdate(cmd.getOfferCode()).orElse(null);
        if (item == null) {
            return withFailure(new InventoryReservationFailed(
                    "ITEM_NOT_FOUND", "No inventory item configured for " + cmd.getOfferCode()));
        }
        if (!item.canReserve(cmd.getQuantity())) {
            String reason = item.getType() == ProductType.SOFTWARE_LICENSE ? "POOL_EXHAUSTED" : "OUT_OF_STOCK";
            log.warn("Reserve FAILED ({}): offerCode={}, available={}", reason, item.getOfferCode(), item.getAvailable());
            return withFailure(new InventoryReservationFailed(reason, capacityDetail(item, cmd.getQuantity())));
        }

        String reservationId = UUID.randomUUID().toString();
        Instant until = Instant.now().plus(RESERVATION_TTL);
        String key = claimKeyIfLicense(item, reservationId);
        if (item.getType() == ProductType.SOFTWARE_LICENSE && key == null) {
            return withFailure(new InventoryReservationFailed("POOL_EXHAUSTED", "No free activation keys for " + item.getOfferCode()));
        }

        item.reserve(cmd.getQuantity());
        items.save(item);
        reservations.save(new Reservation(reservationId, item.getOfferCode(), cmd.getQuantity(), key, until));
        log.info("Reserved {} ({}): offerCode={}, reservationId={}, key={}, until={}",
                cmd.getQuantity(), item.getType(), item.getOfferCode(), reservationId, key, until);
        return withSuccess(new InventoryReserved(reservationId, item.getType().name(), key, until));
    }

    // ── Commit (RESERVED → ALLOCATED, after payment auth) ────────────────────

    @Transactional
    public Message commitInventory(CommandMessage<CommitInventoryCommand> cm) {
        String reservationId = cm.getCommand().getReservationId();
        Reservation res = reservations.findById(reservationId).orElse(null);
        if (res == null) {
            return withFailure(new InventoryCommitFailed("RESERVATION_NOT_FOUND", "Unknown reservationId " + reservationId));
        }
        if (res.isAllocated()) {
            log.info("Commit no-op (already allocated): reservationId={}", reservationId);
            return withSuccess(new InventoryCommitted(reservationId));
        }
        if (res.isReleased()) {
            return withFailure(new InventoryCommitFailed("RESERVATION_RELEASED", "Reservation already released: " + reservationId));
        }
        items.findByOfferCodeForUpdate(res.getOfferCode()).ifPresent(item -> {
            item.commit(res.getQuantity());
            items.save(item);
        });
        res.markAllocated();   // license key stays ALLOCATED (claimed at reserve)
        reservations.save(res);
        log.info("Committed reservation: reservationId={}, offerCode={}", reservationId, res.getOfferCode());
        return withSuccess(new InventoryCommitted(reservationId));
    }

    // ── Allocate (firm, one step, BILL_TO_MOBILE) ────────────────────────────

    @Transactional
    public Message allocateInventory(CommandMessage<AllocateInventoryCommand> cm) {
        AllocateInventoryCommand cmd = cm.getCommand();
        InventoryItem item = items.findByOfferCodeForUpdate(cmd.getOfferCode()).orElse(null);
        if (item == null) {
            return withFailure(new InventoryAllocationFailed(
                    "ITEM_NOT_FOUND", "No inventory item configured for " + cmd.getOfferCode()));
        }
        if (!item.canReserve(cmd.getQuantity())) {
            String reason = item.getType() == ProductType.SOFTWARE_LICENSE ? "POOL_EXHAUSTED" : "OUT_OF_STOCK";
            log.warn("Allocate FAILED ({}): offerCode={}, available={}", reason, item.getOfferCode(), item.getAvailable());
            return withFailure(new InventoryAllocationFailed(reason, capacityDetail(item, cmd.getQuantity())));
        }

        String reservationId = UUID.randomUUID().toString();
        String key = claimKeyIfLicense(item, reservationId);
        if (item.getType() == ProductType.SOFTWARE_LICENSE && key == null) {
            return withFailure(new InventoryAllocationFailed("POOL_EXHAUSTED", "No free activation keys for " + item.getOfferCode()));
        }

        item.allocate(cmd.getQuantity());
        items.save(item);
        reservations.save(Reservation.allocated(reservationId, item.getOfferCode(), cmd.getQuantity(), key));
        log.info("Allocated {} ({}): offerCode={}, reservationId={}, key={}",
                cmd.getQuantity(), item.getType(), item.getOfferCode(), reservationId, key);
        return withSuccess(new InventoryAllocated(reservationId, item.getType().name(), key));
    }

    // ── Release (compensation / sweeper) ─────────────────────────────────────

    @Transactional
    public Message releaseInventory(CommandMessage<ReleaseInventoryCommand> cm) {
        releaseById(cm.getCommand().getReservationId());
        return withSuccess();
    }

    /** Shared release logic, reused by the expiry sweeper. Idempotent via status. */
    @Transactional
    public void releaseById(String reservationId) {
        reservations.findById(reservationId).ifPresentOrElse(res -> {
            if (res.isReleased()) {
                log.info("Release no-op (already released): reservationId={}", reservationId);
                return;
            }
            boolean wasReserved = res.isReserved();
            items.findByOfferCodeForUpdate(res.getOfferCode()).ifPresent(item -> {
                if (wasReserved) item.releaseReserved(res.getQuantity());
                else             item.releaseAllocated(res.getQuantity());
                items.save(item);
                log.info("Released {} unit(s) [{}]: offerCode={}, available={}",
                        res.getQuantity(), wasReserved ? "RESERVED" : "ALLOCATED", item.getOfferCode(), item.getAvailable());
            });
            if (res.getLicenseKey() != null) {
                licenseKeys.findById(res.getLicenseKey()).ifPresent(key -> {
                    key.free();
                    licenseKeys.save(key);
                    log.info("Freed license key {} (reservationId={})", key.getLicenseKey(), reservationId);
                });
            }
            res.markReleased();
            reservations.save(res);
        }, () -> log.info("Release no-op (unknown reservationId={})", reservationId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Claim the next FREE key under a pessimistic lock; null if none / not a license. */
    private String claimKeyIfLicense(InventoryItem item, String reservationId) {
        if (item.getType() != ProductType.SOFTWARE_LICENSE) return null;
        List<LicenseKey> free = licenseKeys.findByOfferCodeAndStatusForUpdate(
                item.getOfferCode(), LicenseKey.Status.FREE, Pageable.ofSize(1));
        if (free.isEmpty()) return null;
        LicenseKey key = free.get(0);
        key.allocate(reservationId);
        licenseKeys.save(key);
        return key.getLicenseKey();
    }

    private static String capacityDetail(InventoryItem item, int qty) {
        return "Insufficient inventory for " + item.getOfferCode()
                + " (available=" + item.getAvailable() + ", requested=" + qty + ")";
    }
}
