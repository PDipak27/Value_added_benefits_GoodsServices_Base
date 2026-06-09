package com.vab.order.query.projection;

import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderFailed;
import com.vab.events.order.OrderPlaced;
import com.vab.order.command.domain.Order;
import com.vab.order.query.document.OrderView;
import com.vab.order.query.repository.OrderViewRepository;
import io.eventuate.tram.events.subscriber.DomainEventEnvelope;
import io.eventuate.tram.events.subscriber.DomainEventHandlers;
import io.eventuate.tram.events.subscriber.DomainEventHandlersBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

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

    private final OrderViewRepository repo;

    public OrderProjector(OrderViewRepository repo) {
        this.repo = repo;
    }

    /** Handler registration consumed by the DomainEventDispatcher bean. */
    public DomainEventHandlers domainEventHandlers() {
        return DomainEventHandlersBuilder
                .forAggregateType(Order.AGGREGATE_TYPE)
                .onEvent(OrderPlaced.class, this::onPlaced)
                .onEvent(OrderConfirmed.class, this::onConfirmed)
                .onEvent(OrderFailed.class, this::onFailed)
                .build();
    }

    private void onPlaced(DomainEventEnvelope<OrderPlaced> de) {
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
        view.setAmount(event.getAmount());
        view.setCurrency(event.getCurrency());
        view.setStatus("PLACED");
        view.setPlacedAt(Instant.now());
        view.setVersion(version);
        view.addTimelineEntry(Instant.now(), "PLACED");

        repo.save(view);
        log.info("Saved OrderView (PLACED): orderId={}", orderId);
    }

    private void onConfirmed(DomainEventEnvelope<OrderConfirmed> de) {
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
            view.setVersion(version);
            view.addTimelineEntry(event.getConfirmedAt(), "CONFIRMED");
            repo.save(view);
            log.info("Saved OrderView (CONFIRMED): orderId={}", orderId);
        }, () -> log.warn("OrderConfirmed for unknown orderId={} (no PLACED view yet)", orderId));
    }

    private void onFailed(DomainEventEnvelope<OrderFailed> de) {
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
}
