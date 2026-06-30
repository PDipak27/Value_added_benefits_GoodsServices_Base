package com.vab.order.query.projection;

import com.vab.events.order.OrderCancelled;
import com.vab.events.order.OrderCancelledRefunded;
import com.vab.events.order.OrderCompleted;
import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderEntitlementRevoked;
import com.vab.events.order.OrderFailed;
import com.vab.events.order.OrderFulfilmentFailed;
import com.vab.events.order.OrderPlaced;
import com.vab.order.command.domain.Order;
import com.vab.order.query.document.EntitlementView;
import com.vab.order.query.document.OrderView;
import com.vab.order.query.repository.EntitlementViewRepository;
import com.vab.order.query.repository.OrderViewRepository;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import io.eventuate.tram.events.subscriber.DomainEventHandlers;
import io.eventuate.tram.events.subscriber.DomainEventHandlersBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Read-side projector — consumes Order domain events from Kafka (relayed from
 * the Tram outbox by Eventuate CDC) and upserts the MongoDB read model
 * (post-DD-14).
 *
 * <p>The handler set is registered as an Eventuate Tram {@code DomainEventDispatcher}
 * (see {@code OrderServiceApplication}); the dispatcher id is the Kafka consumer
 * group.
 *
 * <p>Out-of-order protection: each event carries the aggregate's JPA
 * {@code @Version}; updates are applied only when the incoming version is newer
 * than the version already stored on the view.
 */
@Component
public class OrderProjector {

    private static final Logger log = LoggerFactory.getLogger(OrderProjector.class);

    /** Product types that materialise an entitlement ("benefit"). */
    private static final Set<String> BENEFIT_TYPES = Set.of("DIGITAL_SUBSCRIPTION", "SOFTWARE_LICENSE");

    private final OrderViewRepository       repo;
    private final EntitlementViewRepository entRepo;

    public OrderProjector(OrderViewRepository repo, EntitlementViewRepository entRepo) {
        this.repo    = repo;
        this.entRepo = entRepo;
    }

    /** Handler registration consumed by the DomainEventDispatcher bean. */
    public DomainEventHandlers domainEventHandlers() {
        return DomainEventHandlersBuilder
                .forAggregateType(Order.AGGREGATE_TYPE)
                .onEvent(OrderPlaced.class, this::onPlaced)
                .onEvent(OrderConfirmed.class, this::onConfirmed)
                .onEvent(OrderCompleted.class, this::onCompleted)
                .onEvent(OrderCancelled.class, this::onCancelled)
                .onEvent(OrderCancelledRefunded.class, this::onCancelledRefunded)
                .onEvent(OrderFailed.class, this::onFailed)
                .onEvent(OrderFulfilmentFailed.class, this::onFulfilmentFailed)
                .onEvent(OrderEntitlementRevoked.class, this::onEntitlementRevoked)
                .build();
    }

    // Package-private (not private) so the projection logic can be unit-tested
    // directly; the methods are still only wired via domainEventHandlers().
    void onPlaced(DomainEventEnvelope<OrderPlaced> de) {
        OrderPlaced event   = de.getEvent();
        String      orderId = de.getAggregateId();
        long        version = event.getVersion();

        log.info("Projecting OrderPlaced: orderId={}, offerCode={}, version={}",
                orderId, event.getOfferCode(), version);

        OrderView view = repo.findById(orderId).orElseGet(OrderView::new);
        if (view.getOrderId() != null && version <= view.getVersion()) {
            log.info("Skipping stale OrderPlaced: orderId={}, incoming={}, current={}",
                    orderId, version, view.getVersion());
            return;
        }

        view.setOrderId(orderId);
        view.setSubscriberId(event.getSubscriberId());
        view.setOfferCode(event.getOfferCode());
        view.setProductType(event.getProductType());
        view.setAmount(event.getAmount());
        view.setCurrency(event.getCurrency());
        view.setStatus("PLACED");
        view.setPlacedAt(Instant.now());
        view.setVersion(version);
        view.addTimelineEntry(Instant.now(), "PLACED");

        repo.save(view);
        log.info("Saved OrderView (PLACED): orderId={}", orderId);
    }

    void onConfirmed(DomainEventEnvelope<OrderConfirmed> de) {
        OrderConfirmed event   = de.getEvent();
        String         orderId = de.getAggregateId();
        long           version = event.getVersion();

        log.info("Projecting OrderConfirmed: orderId={}, version={}", orderId, version);

        repo.findById(orderId).ifPresentOrElse(view -> {
            if (version <= view.getVersion()) {
                log.info("Skipping stale OrderConfirmed: orderId={}, incoming={}, current={}",
                        orderId, version, view.getVersion());
                return;
            }
            view.setStatus("CONFIRMED");
            view.setConfirmedAt(event.getConfirmedAt());
            view.setProductType(event.getProductType());
            view.setVersion(version);
            view.addTimelineEntry(event.getConfirmedAt(), "CONFIRMED");
            repo.save(view);
            log.info("Saved OrderView (CONFIRMED): orderId={}", orderId);
        }, () -> log.warn("OrderConfirmed for unknown orderId={} (no PLACED view yet)", orderId));
    }

    void onCompleted(DomainEventEnvelope<OrderCompleted> de) {
        OrderCompleted event   = de.getEvent();
        String         orderId = de.getAggregateId();
        long           version = event.getVersion();

        log.info("Projecting OrderCompleted: orderId={}, version={}", orderId, version);

        repo.findById(orderId).ifPresentOrElse(view -> {
            if (version <= view.getVersion()) {
                log.info("Skipping stale OrderCompleted: orderId={}, incoming={}, current={}",
                        orderId, version, view.getVersion());
                return;
            }
            view.setStatus("COMPLETED");
            view.setCompletedAt(event.getCompletedAt());
            view.setProductType(event.getProductType());
            view.setFulfilment(new OrderView.Fulfilment(
                    event.getProductType(), event.getTrackingRef(),
                    event.getActivationKey(), event.getExternalRef()));
            view.setVersion(version);
            view.addTimelineEntry(event.getCompletedAt(), "COMPLETED");
            repo.save(view);
            log.info("Saved OrderView (COMPLETED): orderId={}", orderId);
            activateEntitlement(orderId, view, event);
        }, () -> log.warn("OrderCompleted for unknown orderId={} (no view yet)", orderId));
    }

    /**
     * Materialise/refresh the subscriber's entitlement for a completed benefit order
     * (DIGITAL_SUBSCRIPTION / SOFTWARE_LICENSE). Idempotent on {@code sourceOrderId}
     * with the same monotonic version guard; the partial unique index is the
     * cross-order safety net (a DuplicateKey just means another active entitlement
     * for this subscriber+offer already exists).
     */
    private void activateEntitlement(String orderId, OrderView view, OrderCompleted event) {
        if (!BENEFIT_TYPES.contains(event.getProductType())) return;
        entRepo.findBySourceOrderId(orderId).ifPresentOrElse(ent -> {
            if (event.getVersion() <= ent.getVersion()) return;   // stale redelivery
            applyActive(ent, view, event);
            entRepo.save(ent);
            log.info("Refreshed entitlement (ACTIVE): orderId={}, offerCode={}", orderId, view.getOfferCode());
        }, () -> {
            EntitlementView ent = new EntitlementView();
            ent.setEntitlementId("ent_" + UUID.randomUUID().toString().replace("-", ""));
            ent.setSourceOrderId(orderId);
            applyActive(ent, view, event);
            try {
                entRepo.save(ent);
                log.info("Activated entitlement: orderId={}, offerCode={}, subscriberId={}",
                        orderId, view.getOfferCode(), view.getSubscriberId());
            } catch (DuplicateKeyException dup) {
                log.warn("Active entitlement already exists for subscriberId={}, offerCode={} — skipping (orderId={})",
                        view.getSubscriberId(), view.getOfferCode(), orderId);
            }
        });
    }

    private static void applyActive(EntitlementView ent, OrderView view, OrderCompleted event) {
        ent.setSubscriberId(view.getSubscriberId());
        ent.setOfferCode(view.getOfferCode());
        ent.setProductType(event.getProductType());
        ent.setStatus("ACTIVE");
        ent.setExternalRef(event.getExternalRef());
        ent.setActivationKey(event.getActivationKey());
        ent.setActivatedAt(event.getCompletedAt());
        ent.setValidFrom(event.getValidFrom());
        ent.setValidUntil(event.getValidUntil());
        ent.setVersion(event.getVersion());
    }

    /** Admin revoke (Phase 3): flip the entitlement to REVOKED (frees the uniqueness slot). */
    void onEntitlementRevoked(DomainEventEnvelope<OrderEntitlementRevoked> de) {
        String orderId = de.getAggregateId();
        long   version = de.getEvent().getVersion();
        log.info("Projecting OrderEntitlementRevoked: orderId={}, version={}", orderId, version);
        entRepo.findBySourceOrderId(orderId).ifPresent(ent -> {
            if (version <= ent.getVersion()) {
                log.info("Skipping stale revoke: orderId={}, incoming={}, current={}",
                        orderId, version, ent.getVersion());
                return;
            }
            ent.setStatus("REVOKED");
            ent.setVersion(version);
            entRepo.save(ent);
            log.info("Entitlement REVOKED in read model: orderId={}, offerCode={}", orderId, ent.getOfferCode());
        });
    }

    void onCancelled(DomainEventEnvelope<OrderCancelled> de) {
        OrderCancelled event   = de.getEvent();
        String         orderId = de.getAggregateId();
        long           version = event.getVersion();

        log.info("Projecting OrderCancelled: orderId={}, version={}", orderId, version);

        repo.findById(orderId).ifPresentOrElse(view -> {
            if (version <= view.getVersion()) {
                log.info("Skipping stale OrderCancelled: orderId={}, incoming={}, current={}",
                        orderId, version, view.getVersion());
                return;
            }
            view.setStatus("CANCELLED");
            view.setVersion(version);
            view.addTimelineEntry(event.getCancelledAt(), "CANCELLED");
            repo.save(view);
            log.info("Saved OrderView (CANCELLED): orderId={}", orderId);
        }, () -> log.warn("OrderCancelled for unknown orderId={} (no view yet)", orderId));
    }

    void onCancelledRefunded(DomainEventEnvelope<OrderCancelledRefunded> de) {
        OrderCancelledRefunded event   = de.getEvent();
        String                 orderId = de.getAggregateId();
        long                   version = event.getVersion();

        log.info("Projecting OrderCancelledRefunded: orderId={}, version={}", orderId, version);

        repo.findById(orderId).ifPresentOrElse(view -> {
            if (version <= view.getVersion()) {
                log.info("Skipping stale OrderCancelledRefunded: orderId={}, incoming={}, current={}",
                        orderId, version, view.getVersion());
                return;
            }
            view.setStatus("CANCELLED_REFUNDED");
            view.setVersion(version);
            view.addTimelineEntry(event.getCancelledAt(), "CANCELLED_REFUNDED");
            repo.save(view);
            log.info("Saved OrderView (CANCELLED_REFUNDED): orderId={}", orderId);
        }, () -> log.warn("OrderCancelledRefunded for unknown orderId={} (no view yet)", orderId));
    }

    void onFailed(DomainEventEnvelope<OrderFailed> de) {
        OrderFailed event   = de.getEvent();
        String      orderId = de.getAggregateId();
        long        version = event.getVersion();

        log.info("Projecting OrderFailed: orderId={}, version={}", orderId, version);

        repo.findById(orderId).ifPresentOrElse(view -> {
            if (version <= view.getVersion()) {
                log.info("Skipping stale OrderFailed: orderId={}, incoming={}, current={}",
                        orderId, version, view.getVersion());
                return;
            }
            view.setStatus("FAILED");
            view.setVersion(version);
            view.addTimelineEntry(Instant.now(), "FAILED");
            repo.save(view);
            log.info("Saved OrderView (FAILED): orderId={}", orderId);
        }, () -> log.warn("OrderFailed for unknown orderId={} (no PLACED view yet)", orderId));
    }

    void onFulfilmentFailed(DomainEventEnvelope<OrderFulfilmentFailed> de) {
        OrderFulfilmentFailed event   = de.getEvent();
        String                orderId = de.getAggregateId();
        long                  version = event.getVersion();

        log.info("Projecting OrderFulfilmentFailed: orderId={}, version={}", orderId, version);

        repo.findById(orderId).ifPresentOrElse(view -> {
            if (version <= view.getVersion()) {
                log.info("Skipping stale OrderFulfilmentFailed: orderId={}, incoming={}, current={}",
                        orderId, version, view.getVersion());
                return;
            }
            // Non-terminal park (DD-27): a later OrderCompleted supersedes this.
            view.setStatus("FULFILMENT_FAILED");
            view.setVersion(version);
            view.addTimelineEntry(event.getFailedAt(), "FULFILMENT_FAILED");
            repo.save(view);
            log.info("Saved OrderView (FULFILMENT_FAILED): orderId={}", orderId);
        }, () -> log.warn("OrderFulfilmentFailed for unknown orderId={} (no view yet)", orderId));
    }
}
