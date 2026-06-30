package com.vab.order.query.projection;

import com.vab.events.order.OrderCancelled;
import com.vab.events.order.OrderCancelledRefunded;
import com.vab.events.order.OrderCompleted;
import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderFailed;
import com.vab.events.order.OrderFulfilmentFailed;
import com.vab.events.order.OrderPlaced;
import com.vab.order.command.domain.Order;
import com.vab.order.query.document.OrderSearchView;
import com.vab.order.query.repository.OrderSearchViewRepository;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import io.eventuate.tram.events.subscriber.DomainEventHandlers;
import io.eventuate.tram.events.subscriber.DomainEventHandlersBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Ops projector (§B3): maintains the flattened {@code order_search_v1} read model
 * from the SAME order events as {@link OrderProjector}, but on its own consumer
 * group — the CQRS "one stream → many shapes" thesis (each projection rebuildable
 * independently). No timeline; just the searchable header fields + current status,
 * with the same monotonic out-of-order guard.
 */
@Component
public class OrderSearchProjector {

    private static final Logger log = LoggerFactory.getLogger(OrderSearchProjector.class);

    private final OrderSearchViewRepository repo;

    public OrderSearchProjector(OrderSearchViewRepository repo) {
        this.repo = repo;
    }

    public DomainEventHandlers domainEventHandlers() {
        return DomainEventHandlersBuilder
                .forAggregateType(Order.AGGREGATE_TYPE)
                .onEvent(OrderPlaced.class, this::onPlaced)
                .onEvent(OrderConfirmed.class,
                        de -> applyStatus(de.getAggregateId(), "CONFIRMED", de.getEvent().getVersion()))
                .onEvent(OrderCompleted.class,
                        de -> applyStatus(de.getAggregateId(), "COMPLETED", de.getEvent().getVersion()))
                .onEvent(OrderCancelled.class,
                        de -> applyStatus(de.getAggregateId(), "CANCELLED", de.getEvent().getVersion()))
                .onEvent(OrderCancelledRefunded.class,
                        de -> applyStatus(de.getAggregateId(), "CANCELLED_REFUNDED", de.getEvent().getVersion()))
                .onEvent(OrderFailed.class,
                        de -> applyStatus(de.getAggregateId(), "FAILED", de.getEvent().getVersion()))
                .onEvent(OrderFulfilmentFailed.class,
                        de -> applyStatus(de.getAggregateId(), "FULFILMENT_FAILED", de.getEvent().getVersion()))
                .build();
    }

    void onPlaced(DomainEventEnvelope<OrderPlaced> de) {
        OrderPlaced event   = de.getEvent();
        String      orderId = de.getAggregateId();
        long        version = event.getVersion();

        OrderSearchView v = repo.findById(orderId).orElseGet(OrderSearchView::new);
        if (v.getOrderId() != null && version <= v.getVersion()) {
            log.info("Skipping stale OrderPlaced (search): orderId={}, incoming={}, current={}",
                    orderId, version, v.getVersion());
            return;
        }
        v.setOrderId(orderId);
        v.setSubscriberId(event.getSubscriberId());
        v.setOfferCode(event.getOfferCode());
        v.setProductType(event.getProductType());
        v.setAmount(event.getAmount());
        v.setCurrency(event.getCurrency());
        v.setBillingMode(event.getBillingMode());
        v.setStatus("PLACED");
        v.setPlacedAt(Instant.now());
        v.setVersion(version);
        repo.save(v);
        log.info("Saved OrderSearchView (PLACED): orderId={}", orderId);
    }

    void applyStatus(String orderId, String status, long version) {
        repo.findById(orderId).ifPresentOrElse(v -> {
            if (version <= v.getVersion()) {
                log.info("Skipping stale {} (search): orderId={}, incoming={}, current={}",
                        status, orderId, version, v.getVersion());
                return;
            }
            v.setStatus(status);
            v.setVersion(version);
            repo.save(v);
            log.info("Saved OrderSearchView ({}): orderId={}", status, orderId);
        }, () -> log.warn("OrderSearchView {} for unknown orderId={} (no PLACED yet)", status, orderId));
    }
}
