package com.vab.order.query.projection;

import com.vab.events.order.OrderPlaced;
import com.vab.order.query.document.OrderSearchView;
import com.vab.order.query.repository.OrderSearchViewRepository;
import io.eventuate.tram.events.common.DomainEvent;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Ops projection logic (§B3): the flattened order_search_v1 is created on
 * OrderPlaced and its status advanced by later events, under the same monotonic
 * out-of-order guard as orders_v1.
 */
@ExtendWith(MockitoExtension.class)
class OrderSearchProjectorTest {

    @Mock OrderSearchViewRepository repo;

    private OrderSearchProjector projector;

    @BeforeEach
    void setUp() {
        projector = new OrderSearchProjector(repo);
    }

    @SuppressWarnings("unchecked")
    private static <E extends DomainEvent> DomainEventEnvelope<E> envelope(String orderId, E event) {
        DomainEventEnvelope<E> de = mock(DomainEventEnvelope.class);
        when(de.getAggregateId()).thenReturn(orderId);
        when(de.getEvent()).thenReturn(event);
        return de;
    }

    private OrderSearchView saved() {
        ArgumentCaptor<OrderSearchView> c = ArgumentCaptor.forClass(OrderSearchView.class);
        verify(repo).save(c.capture());
        return c.getValue();
    }

    private static OrderSearchView existing(String orderId, long version, String status) {
        OrderSearchView v = new OrderSearchView();
        v.setOrderId(orderId);
        v.setVersion(version);
        v.setStatus(status);
        return v;
    }

    @Test
    void placed_creates_a_flattened_doc() {
        when(repo.findById("ord-1")).thenReturn(Optional.empty());

        projector.onPlaced(envelope("ord-1", new OrderPlaced(
                "sub-1", "OTT_X_1M", "DIGITAL_SUBSCRIPTION", "px-1", 499, "INR", "PAY_NOW", "idem-1", 0)));

        OrderSearchView v = saved();
        assertThat(v.getOrderId()).isEqualTo("ord-1");
        assertThat(v.getStatus()).isEqualTo("PLACED");
        assertThat(v.getOfferCode()).isEqualTo("OTT_X_1M");
        assertThat(v.getBillingMode()).isEqualTo("PAY_NOW");
        assertThat(v.getPlacedAt()).isNotNull();
    }

    @Test
    void status_event_advances_the_doc() {
        when(repo.findById("ord-1")).thenReturn(Optional.of(existing("ord-1", 1, "PLACED")));

        projector.applyStatus("ord-1", "COMPLETED", 2);

        OrderSearchView v = saved();
        assertThat(v.getStatus()).isEqualTo("COMPLETED");
        assertThat(v.getVersion()).isEqualTo(2);
    }

    @Test
    void stale_status_is_skipped() {
        when(repo.findById("ord-1")).thenReturn(Optional.of(existing("ord-1", 3, "COMPLETED")));

        projector.applyStatus("ord-1", "CONFIRMED", 2);

        verify(repo, never()).save(any());
    }

    @Test
    void status_for_unknown_order_is_a_noop() {
        when(repo.findById("missing")).thenReturn(Optional.empty());

        projector.applyStatus("missing", "CONFIRMED", 2);

        verify(repo, never()).save(any());
    }
}
