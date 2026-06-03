package com.vab.order.query.projection;

import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderFailed;
import com.vab.events.order.OrderPlaced;
import com.vab.order.query.document.OrderView;
import com.vab.order.query.repository.OrderViewRepository;
import io.eventuate.DispatchedEvent;
import io.eventuate.EventHandlerMethod;
import io.eventuate.EventSubscriber;
import com.vab.order.command.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Event-driven projector — consumes Order aggregate events published by
 * Eventuate CDC (Postgres WAL → Kafka) and upserts the MongoDB read model.
 *
 * @EventSubscriber registers this as an Eventuate event handler.
 * id must be unique per subscriber group (used as Kafka consumer group id).
 *
 * Out-of-order protection: skip events older than the current version.
 */
@EventSubscriber(id = "orderServiceProjector")
@Component
public class OrderProjector {

    private static final Logger log = LoggerFactory.getLogger(OrderProjector.class);

    private final OrderViewRepository repo;

    public OrderProjector(OrderViewRepository repo) {
        this.repo = repo;
    }

    @EventHandlerMethod
    public void onPlaced(DispatchedEvent<OrderPlaced> de) {
        OrderPlaced event   = de.getEvent();
        String      orderId = de.getEntityId();
        String      version = de.getEventId().asString();

        log.info("Projecting OrderPlaced: orderId={}, subscriberId={}, offerCode={}, version={}",
                orderId, event.getSubscriberId(), event.getOfferCode(), version);

        OrderView view = new OrderView();
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
        log.info("Saved OrderView (PLACED) to MongoDB: orderId={}", orderId);
    }

    @EventHandlerMethod
    public void onConfirmed(DispatchedEvent<OrderConfirmed> de) {
        String  orderId = de.getEntityId();
        String  version = de.getEventId().asString();

        log.info("Projecting OrderConfirmed: orderId={}, version={}", orderId, version);

        repo.findById(orderId).ifPresentOrElse(view -> {
            // skip stale: Int128 stringified is fixed-width hex; lexicographic order = arrival order
            if (view.getVersion() != null && version.compareTo(view.getVersion()) <= 0) {
                log.info("Skipping stale OrderConfirmed: orderId={}, incoming={}, current={}",
                        orderId, version, view.getVersion());
                return;
            }

            view.setStatus("CONFIRMED");
            view.setConfirmedAt(de.getEvent().getConfirmedAt());
            view.setVersion(version);
            view.addTimelineEntry(de.getEvent().getConfirmedAt(), "CONFIRMED");
            repo.save(view);
            log.info("Saved OrderView (CONFIRMED) to MongoDB: orderId={}", orderId);
        }, () -> log.warn("OrderConfirmed for unknown orderId={} (no PLACED view yet)", orderId));
    }

    @EventHandlerMethod
    public void onFailed(DispatchedEvent<OrderFailed> de) {
        String orderId = de.getEntityId();
        String version = de.getEventId().asString();

        log.info("Projecting OrderFailed: orderId={}, version={}", orderId, version);

        repo.findById(orderId).ifPresentOrElse(view -> {
            if (view.getVersion() != null && version.compareTo(view.getVersion()) <= 0) {
                log.info("Skipping stale OrderFailed: orderId={}, incoming={}, current={}",
                        orderId, version, view.getVersion());
                return;
            }

            view.setStatus("FAILED");
            view.setVersion(version);
            view.addTimelineEntry(Instant.now(), "FAILED");
            repo.save(view);
            log.info("Saved OrderView (FAILED) to MongoDB: orderId={}", orderId);
        }, () -> log.warn("OrderFailed for unknown orderId={} (no PLACED view yet)", orderId));
    }
}
