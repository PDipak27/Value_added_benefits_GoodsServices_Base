package com.vab.order.command.service;

import com.vab.events.order.OrderCancelled;
import com.vab.events.order.OrderCancelledRefunded;
import com.vab.events.order.OrderCompleted;
import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderEntitlementRevoked;
import com.vab.events.order.OrderFailed;
import com.vab.events.order.OrderPlaced;
import com.vab.order.command.catalog.CatalogClient;
import com.vab.order.command.domain.Order;
import com.vab.order.command.domain.OrderRepository;
import com.vab.order.command.domain.OrderStatus;
import com.vab.order.command.domain.PlaceOrderCommand;
import com.vab.order.idempotency.IdempotencyKey;
import com.vab.order.idempotency.IdempotencyKeyRepository;
import com.vab.order.saga.PlaceOrderSaga;
import com.vab.order.saga.PlaceOrderSagaData;
import io.eventuate.tram.events.common.DomainEvent;
import io.eventuate.tram.events.publisher.DomainEventPublisher;
import io.eventuate.tram.sagas.orchestration.SagaInstanceFactory;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Write-side behaviour of the Order aggregate service: idempotency, catalog
 * product-type resolution (fail-open), and the find → mutate → save → publish
 * shape of each saga callback. All collaborators are mocked.
 */
@ExtendWith(MockitoExtension.class)
class OrderCommandServiceTest {

    @Mock OrderRepository orderRepo;
    @Mock DomainEventPublisher publisher;
    @Mock SagaInstanceFactory sagaInstanceFactory;
    @Mock PlaceOrderSaga placeOrderSaga;
    @Mock IdempotencyKeyRepository idempotencyRepo;
    @Mock CatalogClient catalogClient;
    @Mock com.vab.order.command.fulfilment.FulfilmentReDrive fulfilmentReDrive;
    @Mock com.vab.order.query.repository.EntitlementViewRepository entitlementRepo;
    @Mock com.vab.order.command.fulfilment.EntitlementRevoke entitlementRevoke;

    private OrderCommandService service;

    @BeforeEach
    void setUp() {
        service = new OrderCommandService(orderRepo, publisher, sagaInstanceFactory,
                placeOrderSaga, idempotencyRepo, catalogClient, fulfilmentReDrive, entitlementRepo,
                entitlementRevoke);
    }

    private static PlaceOrderCommand placeCmd(String productType) {
        return new PlaceOrderCommand("sub-1", "OFF-1", productType,
                "px-1", 500, "INR", "PAY_NOW", "idem-1");
    }

    /** Capture the events published for the given aggregate id. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<DomainEvent> publishedEvents() {
        ArgumentCaptor<List<DomainEvent>> events = ArgumentCaptor.forClass((Class) List.class);
        verify(publisher).publish(eq(Order.AGGREGATE_TYPE), org.mockito.ArgumentMatchers.anyString(), events.capture());
        return events.getValue();
    }

    @Nested
    class PlaceOrder {

        @Test
        void idempotency_hit_returns_existing_order_with_no_side_effects() {
            when(idempotencyRepo.findBySubscriberIdAndIdempotencyKey("sub-1", "idem-1"))
                    .thenReturn(Optional.of(new IdempotencyKey("sub-1", "idem-1", "ord-existing")));

            String orderId = service.placeOrder(placeCmd("PHYSICAL_GOOD"));

            assertThat(orderId).isEqualTo("ord-existing");
            verifyNoInteractions(orderRepo, publisher, sagaInstanceFactory, catalogClient);
        }

        @Test
        void miss_persists_publishes_starts_saga_and_stores_key() {
            when(idempotencyRepo.findBySubscriberIdAndIdempotencyKey("sub-1", "idem-1"))
                    .thenReturn(Optional.empty());
            when(catalogClient.resolveOffer("OFF-1"))
                    .thenReturn(new CatalogClient.OfferDetail("PHYSICAL_GOOD", null));

            String orderId = service.placeOrder(placeCmd("PHYSICAL_GOOD"));

            assertThat(orderId).startsWith("ord_");
            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepo).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getId()).isEqualTo(orderId);
            assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.PLACED);

            assertThat(publishedEvents().get(0)).isInstanceOf(OrderPlaced.class);
            verify(sagaInstanceFactory).create(eq(placeOrderSaga), any(PlaceOrderSagaData.class));
            verify(idempotencyRepo).save(any(IdempotencyKey.class));
        }

        @Test
        void catalog_value_overrides_client_product_type() {
            when(idempotencyRepo.findBySubscriberIdAndIdempotencyKey("sub-1", "idem-1"))
                    .thenReturn(Optional.empty());
            // Client claimed DIGITAL but catalog (authoritative) says SOFTWARE_LICENSE.
            when(catalogClient.resolveOffer("OFF-1"))
                    .thenReturn(new CatalogClient.OfferDetail("SOFTWARE_LICENSE", 12));

            service.placeOrder(placeCmd("DIGITAL_SUBSCRIPTION"));

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepo).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getProductType()).isEqualTo("SOFTWARE_LICENSE");
        }

        @Test
        void catalog_unreachable_keeps_client_product_type_fail_open() {
            when(idempotencyRepo.findBySubscriberIdAndIdempotencyKey("sub-1", "idem-1"))
                    .thenReturn(Optional.empty());
            when(catalogClient.resolveOffer("OFF-1")).thenReturn(null); // fail-open

            service.placeOrder(placeCmd("DIGITAL_SUBSCRIPTION"));

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepo).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getProductType()).isEqualTo("DIGITAL_SUBSCRIPTION");
        }

        @Test
        void snapshots_offer_term_into_saga_data() {
            when(idempotencyRepo.findBySubscriberIdAndIdempotencyKey("sub-1", "idem-1"))
                    .thenReturn(Optional.empty());
            when(catalogClient.resolveOffer("OFF-1"))
                    .thenReturn(new CatalogClient.OfferDetail("DIGITAL_SUBSCRIPTION", 6));

            service.placeOrder(placeCmd("DIGITAL_SUBSCRIPTION"));

            ArgumentCaptor<PlaceOrderSagaData> data = ArgumentCaptor.forClass(PlaceOrderSagaData.class);
            verify(sagaInstanceFactory).create(eq(placeOrderSaga), data.capture());
            assertThat(data.getValue().getTermMonths()).isEqualTo(6);
        }

        @Test
        void duplicate_active_entitlement_is_rejected_409() {
            when(idempotencyRepo.findBySubscriberIdAndIdempotencyKey("sub-1", "idem-1"))
                    .thenReturn(Optional.empty());
            when(entitlementRepo.existsBySubscriberIdAndOfferCodeAndStatus("sub-1", "OFF-1", "ACTIVE"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.placeOrder(placeCmd("DIGITAL_SUBSCRIPTION")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("409");

            verifyNoInteractions(orderRepo, sagaInstanceFactory);
        }
    }

    @Nested
    class SagaCallbacks {

        private Order placed() {
            return Order.place("ord-1", "sub-1", "OFF-1", "PHYSICAL_GOOD", "px-1", 500, "INR", "PAY_NOW");
        }

        @Test
        void confirmOrder_confirms_and_publishes_order_confirmed() {
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(placed()));

            service.confirmOrder("ord-1", "PHYSICAL_GOOD");

            verify(orderRepo).saveAndFlush(any(Order.class));
            assertThat(publishedEvents().get(0)).isInstanceOf(OrderConfirmed.class);
        }

        @Test
        void completeOrder_completes_and_publishes_order_completed() {
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(placed()));

            service.completeOrder("ord-1", "PHYSICAL_GOOD", "TRK9", null, null);

            assertThat(publishedEvents().get(0)).isInstanceOf(OrderCompleted.class);
        }

        @Test
        void cancel_marks_cancelled_and_publishes_order_cancelled() {
            Order order = placed();
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(order));

            service.cancel("ord-1", "USER_CANCEL: before pivot");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(publishedEvents().get(0)).isInstanceOf(OrderCancelled.class);
        }

        @Test
        void cancelRefunded_marks_cancelled_refunded_and_publishes_event() {
            Order order = placed();
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(order));

            service.cancelRefunded("ord-1", "FULFIL_FAILED: DELIVERY_FAILED");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED_REFUNDED);
            assertThat(publishedEvents().get(0)).isInstanceOf(OrderCancelledRefunded.class);
        }

        @Test
        void requestCancel_flags_a_non_terminal_order() {
            Order order = placed();
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(order));

            service.requestCancel("ord-1");

            assertThat(order.isCancelRequested()).isTrue();
            verify(orderRepo).saveAndFlush(order);
        }

        @Test
        void isCancelRequested_reads_the_flag() {
            Order order = placed();
            order.requestCancel();
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(order));

            assertThat(service.isCancelRequested("ord-1")).isTrue();
        }

        @Test
        void failOrder_fails_and_publishes_order_failed() {
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(placed()));

            service.failOrder("ord-1", "RESERVE_INVENTORY", "OUT_OF_STOCK");

            assertThat(publishedEvents().get(0)).isInstanceOf(OrderFailed.class);
        }

        @Test
        void callback_on_unknown_order_throws() {
            when(orderRepo.findById("missing")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.confirmOrder("missing", "PHYSICAL_GOOD"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class EntitlementRevokeFlow {

        private Order completedDigital() {
            Order o = Order.place("ord-1", "sub-1", "OFF-1", "DIGITAL_SUBSCRIPTION", "px-1", 499, "INR", "PAY_NOW");
            o.complete(Instant.now(), null, null, "OTT-1");
            return o;
        }

        @Test
        void revoke_request_on_completed_benefit_sends_command() {
            Order order = completedDigital();
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(order));

            service.requestEntitlementRevoke("ord-1");

            verify(entitlementRevoke).revoke(order);
        }

        @Test
        void revoke_request_on_non_completed_is_409() {
            // PLACED, not COMPLETED.
            Order order = Order.place("ord-1", "sub-1", "OFF-1", "DIGITAL_SUBSCRIPTION", "px-1", 499, "INR", "PAY_NOW");
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.requestEntitlementRevoke("ord-1"))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(entitlementRevoke);
        }

        @Test
        void revoke_request_on_physical_good_is_409() {
            Order order = Order.place("ord-1", "sub-1", "OFF-1", "PHYSICAL_GOOD", "px-1", 500, "INR", "PAY_NOW");
            order.complete(Instant.now(), "TRK1", null, null);
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.requestEntitlementRevoke("ord-1"))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(entitlementRevoke);
        }

        @Test
        void apply_revoked_marks_order_and_publishes_event() {
            Order order = completedDigital();
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(order));

            service.applyEntitlementRevoked("ord-1");

            assertThat(order.isEntitlementRevoked()).isTrue();
            assertThat(publishedEvents().get(0)).isInstanceOf(OrderEntitlementRevoked.class);
        }

        @Test
        void apply_revoked_is_idempotent() {
            Order order = completedDigital();
            order.revokeEntitlement(Instant.now());   // already revoked
            when(orderRepo.findById("ord-1")).thenReturn(Optional.of(order));

            service.applyEntitlementRevoked("ord-1");

            verify(orderRepo, never()).saveAndFlush(any());
            verifyNoInteractions(publisher);
        }
    }
}
