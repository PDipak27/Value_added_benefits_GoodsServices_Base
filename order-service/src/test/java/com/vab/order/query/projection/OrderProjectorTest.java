package com.vab.order.query.projection;

import com.vab.events.order.OrderCancelled;
import com.vab.events.order.OrderCancelledRefunded;
import com.vab.events.order.OrderCompleted;
import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderEntitlementRevoked;
import com.vab.events.order.OrderFailed;
import com.vab.events.order.OrderPlaced;
import com.vab.order.query.document.EntitlementView;
import com.vab.order.query.document.OrderView;
import com.vab.order.query.repository.EntitlementViewRepository;
import com.vab.order.query.repository.OrderViewRepository;
import io.eventuate.tram.events.common.DomainEvent;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Read-model projection logic. The Mongo repository is mocked and the
 * {@link DomainEventEnvelope} is stubbed; we assert the upserted {@link OrderView}
 * (status, version, fulfilment) and — crucially — the out-of-order version guard.
 */
@ExtendWith(MockitoExtension.class)
class OrderProjectorTest {

    @Mock OrderViewRepository repo;
    @Mock EntitlementViewRepository entRepo;

    private OrderProjector projector;

    @BeforeEach
    void setUp() {
        projector = new OrderProjector(repo, entRepo);
    }

    @SuppressWarnings("unchecked")
    private static <E extends DomainEvent> DomainEventEnvelope<E> envelope(String orderId, E event) {
        DomainEventEnvelope<E> de = mock(DomainEventEnvelope.class);
        when(de.getAggregateId()).thenReturn(orderId);
        when(de.getEvent()).thenReturn(event);
        return de;
    }

    private OrderView savedView() {
        ArgumentCaptor<OrderView> c = ArgumentCaptor.forClass(OrderView.class);
        verify(repo).save(c.capture());
        return c.getValue();
    }

    /** A view already projected up to {@code version}, used for stale/transition cases. */
    private static OrderView existingView(String orderId, long version, String status) {
        OrderView v = new OrderView();
        v.setOrderId(orderId);
        v.setVersion(version);
        v.setStatus(status);
        return v;
    }

    private static OrderPlaced placed(long version) {
        return new OrderPlaced("sub-1", "OFF-1", "PHYSICAL_GOOD", "px-1", 500, "INR", "PAY_NOW", "idem-1", version);
    }

    @Nested
    class Placed {

        @Test
        void creates_a_new_view_when_none_exists() {
            when(repo.findById("ord-1")).thenReturn(Optional.empty());

            projector.onPlaced(envelope("ord-1", placed(0)));

            OrderView v = savedView();
            assertThat(v.getOrderId()).isEqualTo("ord-1");
            assertThat(v.getStatus()).isEqualTo("PLACED");
            assertThat(v.getVersion()).isZero();
            assertThat(v.getTimeline()).extracting(OrderView.TimelineEntry::getStatus).containsExactly("PLACED");
        }

        @Test
        void skips_a_stale_redelivery() {
            // Existing view already at version 2; a redelivered v1 must not overwrite it.
            when(repo.findById("ord-1")).thenReturn(Optional.of(existingView("ord-1", 2, "CONFIRMED")));

            projector.onPlaced(envelope("ord-1", placed(1)));

            verify(repo, never()).save(any());
        }
    }

    @Nested
    class Confirmed {

        @Test
        void advances_an_existing_view() {
            when(repo.findById("ord-1")).thenReturn(Optional.of(existingView("ord-1", 0, "PLACED")));

            projector.onConfirmed(envelope("ord-1", new OrderConfirmed(Instant.now(), 1, "PHYSICAL_GOOD")));

            assertThat(savedView().getStatus()).isEqualTo("CONFIRMED");
        }

        @Test
        void skips_when_incoming_version_not_newer() {
            when(repo.findById("ord-1")).thenReturn(Optional.of(existingView("ord-1", 1, "CONFIRMED")));

            projector.onConfirmed(envelope("ord-1", new OrderConfirmed(Instant.now(), 1, "PHYSICAL_GOOD")));

            verify(repo, never()).save(any());
        }

        @Test
        void ignores_event_for_unknown_order() {
            when(repo.findById("ord-x")).thenReturn(Optional.empty());

            projector.onConfirmed(envelope("ord-x", new OrderConfirmed(Instant.now(), 1, "PHYSICAL_GOOD")));

            verify(repo, never()).save(any());
        }
    }

    @Test
    void completed_sets_status_and_denormalized_fulfilment_artifact() {
        when(repo.findById("ord-1")).thenReturn(Optional.of(existingView("ord-1", 1, "CONFIRMED")));

        projector.onCompleted(envelope("ord-1",
                new OrderCompleted(Instant.now(), 2, "PHYSICAL_GOOD", "TRK9", null, null)));

        OrderView v = savedView();
        assertThat(v.getStatus()).isEqualTo("COMPLETED");
        assertThat(v.getFulfilment().getTrackingRef()).isEqualTo("TRK9");
        verify(entRepo, never()).save(any());   // PHYSICAL_GOOD is not a benefit type
    }

    @Test
    void completed_digital_subscription_activates_an_entitlement() {
        OrderView view = existingView("ord-9", 1, "CONFIRMED");
        view.setSubscriberId("sub-9");
        view.setOfferCode("OTT_X_1M");
        when(repo.findById("ord-9")).thenReturn(Optional.of(view));
        when(entRepo.findBySourceOrderId("ord-9")).thenReturn(Optional.empty());

        projector.onCompleted(envelope("ord-9",
                new OrderCompleted(Instant.now(), 2, "DIGITAL_SUBSCRIPTION", null, null, "OTT-REF9")));

        ArgumentCaptor<EntitlementView> c = ArgumentCaptor.forClass(EntitlementView.class);
        verify(entRepo).save(c.capture());
        EntitlementView ent = c.getValue();
        assertThat(ent.getStatus()).isEqualTo("ACTIVE");
        assertThat(ent.getSubscriberId()).isEqualTo("sub-9");
        assertThat(ent.getOfferCode()).isEqualTo("OTT_X_1M");
        assertThat(ent.getExternalRef()).isEqualTo("OTT-REF9");
        assertThat(ent.getSourceOrderId()).isEqualTo("ord-9");
        assertThat(ent.getEntitlementId()).startsWith("ent_");
    }

    @Test
    void entitlement_revoked_flips_the_read_model_to_revoked() {
        EntitlementView ent = new EntitlementView();
        ent.setVersion(2);
        ent.setStatus("ACTIVE");
        ent.setOfferCode("OTT_X_1M");
        when(entRepo.findBySourceOrderId("ord-9")).thenReturn(Optional.of(ent));

        projector.onEntitlementRevoked(envelope("ord-9", new OrderEntitlementRevoked(Instant.now(), 3)));

        ArgumentCaptor<EntitlementView> c = ArgumentCaptor.forClass(EntitlementView.class);
        verify(entRepo).save(c.capture());
        assertThat(c.getValue().getStatus()).isEqualTo("REVOKED");
        assertThat(c.getValue().getVersion()).isEqualTo(3);
    }

    @Test
    void cancelled_sets_cancelled_status() {
        when(repo.findById("ord-1")).thenReturn(Optional.of(existingView("ord-1", 1, "PLACED")));

        projector.onCancelled(envelope("ord-1",
                new OrderCancelled(Instant.now(), 2, "USER_CANCEL: before pivot")));

        assertThat(savedView().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelled_refunded_sets_cancelled_refunded_status() {
        when(repo.findById("ord-1")).thenReturn(Optional.of(existingView("ord-1", 1, "CONFIRMED")));

        projector.onCancelledRefunded(envelope("ord-1",
                new OrderCancelledRefunded(Instant.now(), 2, "FULFIL_FAILED: DELIVERY_FAILED")));

        assertThat(savedView().getStatus()).isEqualTo("CANCELLED_REFUNDED");
    }

    @Test
    void failed_sets_failed_status() {
        when(repo.findById("ord-1")).thenReturn(Optional.of(existingView("ord-1", 0, "PLACED")));

        projector.onFailed(envelope("ord-1", new OrderFailed("RESERVE_INVENTORY", "OUT_OF_STOCK", 1)));

        assertThat(savedView().getStatus()).isEqualTo("FAILED");
    }
}
