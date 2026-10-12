package com.vab.order.query.projection;

import com.vab.events.order.OrderCompleted;
import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderEntitlementRevoked;
import com.vab.events.order.OrderPlaced;
import com.vab.order.it.AbstractOrderIntegrationTest;
import com.vab.order.query.document.EntitlementView;
import com.vab.order.query.document.OrderView;
import com.vab.order.query.repository.EntitlementViewRepository;
import com.vab.order.query.repository.OrderViewRepository;
import io.eventuate.tram.events.common.DomainEvent;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OrderProjector against a real MongoDB (DD-15): orders_v1 / entitlements_v1 documents, the monotonic
 * version guard (out-of-order + redelivery), and the partial unique index on ACTIVE entitlements that
 * {@code EntitlementIndexConfig} creates at startup. Events are fed straight into the projector
 * (no CDC/Kafka relay in ITs); {@code OrderProjectorTest} covers the same rules with mocks.
 */
class OrderProjectionIT extends AbstractOrderIntegrationTest {

    private static final String SUB = "sub-proj";
    private static final String OFFER = "OTT_NETFLIX_6M";

    @Autowired OrderProjector projector;
    @Autowired OrderViewRepository views;
    @Autowired EntitlementViewRepository entitlements;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        views.deleteAll();
        entitlements.deleteAll();
    }

    @SuppressWarnings("unchecked")
    private static <E extends DomainEvent> DomainEventEnvelope<E> envelope(String orderId, E event) {
        DomainEventEnvelope<E> de = mock(DomainEventEnvelope.class);
        when(de.getAggregateId()).thenReturn(orderId);
        when(de.getEvent()).thenReturn(event);
        return de;
    }

    private static OrderPlaced placed(long version) {
        return new OrderPlaced(SUB, OFFER, "DIGITAL_SUBSCRIPTION", "px-1", 599, "INR", "PAY_NOW", "idem-1", version);
    }

    private static OrderCompleted completed(long version, String externalRef) {
        Instant now = Instant.now();
        return new OrderCompleted(now, version, "DIGITAL_SUBSCRIPTION", null, null, externalRef,
                now, now.plusSeconds(180L * 24 * 3600));
    }

    /** Drives one order PLACED(v0) → CONFIRMED(v1) → COMPLETED(v2). */
    private void projectToCompleted(String orderId, String externalRef) {
        projector.onPlaced(envelope(orderId, placed(0)));
        projector.onConfirmed(envelope(orderId, new OrderConfirmed(Instant.now(), 1, "DIGITAL_SUBSCRIPTION")));
        projector.onCompleted(envelope(orderId, completed(2, externalRef)));
    }

    private List<EntitlementView> activeEntitlements() {
        return entitlements.findBySubscriberIdAndStatus(SUB, "ACTIVE");
    }

    /** The happy lifecycle persists a COMPLETED orders_v1 doc (version 2, 3-step timeline, externalRef) and an ACTIVE entitlement. */
    @Test
    void lifecycle_projects_order_and_activates_entitlement() {
        projectToCompleted("ord-p1", "OTT-P1");

        OrderView view = views.findById("ord-p1").orElseThrow();
        assertThat(view.getStatus()).isEqualTo("COMPLETED");
        assertThat(view.getVersion()).isEqualTo(2);
        assertThat(view.getTimeline()).extracting(OrderView.TimelineEntry::getStatus)
                .containsExactly("PLACED", "CONFIRMED", "COMPLETED");
        assertThat(view.getFulfilment().getExternalRef()).isEqualTo("OTT-P1");

        assertThat(activeEntitlements()).singleElement().satisfies(ent -> {
            assertThat(ent.getSourceOrderId()).isEqualTo("ord-p1");
            assertThat(ent.getExternalRef()).isEqualTo("OTT-P1");
            assertThat(ent.getValidUntil()).isNotNull();
        });
    }

    /** Out-of-order delivery: a CONFIRMED(v1) arriving after COMPLETED(v2) is skipped; the doc stays COMPLETED at v2. */
    @Test
    void older_event_after_newer_is_skipped() {
        projectToCompleted("ord-p2", "OTT-P2");

        projector.onConfirmed(envelope("ord-p2", new OrderConfirmed(Instant.now(), 1, "DIGITAL_SUBSCRIPTION")));

        OrderView view = views.findById("ord-p2").orElseThrow();
        assertThat(view.getStatus()).isEqualTo("COMPLETED");
        assertThat(view.getVersion()).isEqualTo(2);
        assertThat(view.getTimeline()).hasSize(3);
    }

    /** Redelivery: a duplicate OrderPlaced(v0) after the view moved on changes nothing (no reset to PLACED, no extra timeline entry). */
    @Test
    void redelivered_placed_is_ignored() {
        projector.onPlaced(envelope("ord-p3", placed(0)));
        projector.onConfirmed(envelope("ord-p3", new OrderConfirmed(Instant.now(), 1, "DIGITAL_SUBSCRIPTION")));

        projector.onPlaced(envelope("ord-p3", placed(0)));

        OrderView view = views.findById("ord-p3").orElseThrow();
        assertThat(view.getStatus()).isEqualTo("CONFIRMED");
        assertThat(view.getTimeline()).extracting(OrderView.TimelineEntry::getStatus).containsExactly("PLACED", "CONFIRMED");
    }

    /** An event for an order whose PLACED was not projected yet creates no document (it doesn't invent a partial view). */
    @Test
    void event_before_placed_creates_nothing() {
        projector.onConfirmed(envelope("ord-p4", new OrderConfirmed(Instant.now(), 1, "DIGITAL_SUBSCRIPTION")));

        assertThat(views.findById("ord-p4")).isEmpty();
    }

    /** EntitlementIndexConfig created the partial unique index (subscriberId, offerCode) filtered to status=ACTIVE. */
    @Test
    void active_entitlement_partial_unique_index_exists() {
        List<IndexInfo> indexes = mongo.indexOps(EntitlementView.class).getIndexInfo();

        assertThat(indexes).anySatisfy(ix -> {
            assertThat(ix.isUnique()).isTrue();
            assertThat(ix.getIndexFields()).extracting(f -> f.getKey()).containsExactly("subscriberId", "offerCode");
            assertThat(ix.getPartialFilterExpression()).contains("ACTIVE");
        });
    }

    /** Uniqueness layer 2: a second completed order for the same subscriber+offer hits the index and is skipped quietly — one ACTIVE entitlement. */
    @Test
    void second_active_entitlement_for_same_offer_is_rejected_by_index() {
        projectToCompleted("ord-p5a", "OTT-P5A");

        assertThatCode(() -> projectToCompleted("ord-p5b", "OTT-P5B")).doesNotThrowAnyException();

        assertThat(activeEntitlements()).singleElement()
                .extracting(EntitlementView::getSourceOrderId).isEqualTo("ord-p5a");
        assertThat(views.findById("ord-p5b").orElseThrow().getStatus()).isEqualTo("COMPLETED");
    }

    /** Revoke (v3) flips the entitlement to REVOKED, which frees the index slot so a re-purchase can become ACTIVE. */
    @Test
    void revoke_frees_the_slot_for_a_new_active_entitlement() {
        projectToCompleted("ord-p6a", "OTT-P6A");
        projector.onEntitlementRevoked(envelope("ord-p6a", new OrderEntitlementRevoked(Instant.now(), 3)));

        assertThat(entitlements.findBySourceOrderId("ord-p6a").orElseThrow().getStatus()).isEqualTo("REVOKED");

        projectToCompleted("ord-p6b", "OTT-P6B");

        assertThat(activeEntitlements()).singleElement()
                .extracting(EntitlementView::getSourceOrderId).isEqualTo("ord-p6b");
        assertThat(entitlements.count()).isEqualTo(2);
    }

    /** A stale revoke (version not newer than the entitlement's) is ignored: the entitlement stays ACTIVE. */
    @Test
    void stale_revoke_is_ignored() {
        projectToCompleted("ord-p7", "OTT-P7");

        projector.onEntitlementRevoked(envelope("ord-p7", new OrderEntitlementRevoked(Instant.now(), 2)));

        assertThat(entitlements.findBySourceOrderId("ord-p7").orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }
}
