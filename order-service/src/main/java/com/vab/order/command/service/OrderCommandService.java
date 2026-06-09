package com.vab.order.command.service;

import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderFailed;
import com.vab.events.order.OrderPlaced;
import com.vab.order.command.domain.Order;
import com.vab.order.command.domain.OrderRepository;
import com.vab.order.command.domain.PlaceOrderCommand;
import com.vab.order.idempotency.IdempotencyKey;
import com.vab.order.idempotency.IdempotencyKeyRepository;
import com.vab.order.saga.PlaceOrderSaga;
import com.vab.order.saga.PlaceOrderSagaData;
import io.eventuate.tram.events.publisher.DomainEventPublisher;
import io.eventuate.tram.sagas.orchestration.SagaInstanceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Write side of the Order aggregate (post-DD-14).
 *
 * <p>The aggregate is state-stored via JPA; domain events are published through
 * the Eventuate Tram transactional outbox in the <em>same</em> JDBC transaction
 * as the state change, giving the atomic "write + publish" guarantee without
 * event-sourcing the aggregate. Eventuate CDC relays the outbox to Kafka.
 */
@Service
public class OrderCommandService {

    private static final Logger log = LoggerFactory.getLogger(OrderCommandService.class);

    private final OrderRepository          orderRepo;
    private final DomainEventPublisher     domainEventPublisher;
    private final SagaInstanceFactory      sagaInstanceFactory;
    private final PlaceOrderSaga           placeOrderSaga;
    private final IdempotencyKeyRepository idempotencyRepo;

    public OrderCommandService(OrderRepository orderRepo,
                               DomainEventPublisher domainEventPublisher,
                               SagaInstanceFactory sagaInstanceFactory,
                               PlaceOrderSaga placeOrderSaga,
                               IdempotencyKeyRepository idempotencyRepo) {
        this.orderRepo            = orderRepo;
        this.domainEventPublisher = domainEventPublisher;
        this.sagaInstanceFactory  = sagaInstanceFactory;
        this.placeOrderSaga       = placeOrderSaga;
        this.idempotencyRepo      = idempotencyRepo;
    }

    /**
     * Places an order.
     *
     * <p>Idempotency: (subscriberId, idempotencyKey) lookup first. On hit, the
     * stored orderId is returned with no side effects. On miss, the aggregate row
     * is inserted, {@code OrderPlaced} is written to the outbox, the saga is
     * started, and the idempotency key is stored — all in one transaction.
     *
     * @return orderId (new or previously stored)
     */
    @Transactional
    public String placeOrder(PlaceOrderCommand cmd) {
        // ── Idempotency check ──────────────────────────────────────────
        Optional<IdempotencyKey> existing =
                idempotencyRepo.findBySubscriberIdAndIdempotencyKey(
                        cmd.getSubscriberId(), cmd.getIdempotencyKey());

        if (existing.isPresent()) {
            log.info("Idempotency hit: subscriberId={}, key={} -> existing orderId={}",
                    cmd.getSubscriberId(), cmd.getIdempotencyKey(), existing.get().getOrderId());
            return existing.get().getOrderId();
        }

        log.info("Placing order: subscriberId={}, offerCode={}, amount={} {}",
                cmd.getSubscriberId(), cmd.getOfferCode(), cmd.getAmount(), cmd.getCurrency());

        // ── Insert state-stored aggregate ──────────────────────────────
        String orderId = "ord_" + UUID.randomUUID().toString().replace("-", "");
        Order order = Order.place(
                orderId,
                cmd.getSubscriberId(),
                cmd.getOfferCode(),
                cmd.getPriceSnapshotId(),
                cmd.getAmount(),
                cmd.getCurrency(),
                cmd.getBillingMode());
        orderRepo.saveAndFlush(order);   // assigns @Version (0)
        log.info("Order persisted (PLACED): orderId={}, version={}", orderId, order.getVersion());

        // ── Publish OrderPlaced via the Tram outbox (same tx) ──────────
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderPlaced(
                        cmd.getSubscriberId(),
                        cmd.getOfferCode(),
                        cmd.getPriceSnapshotId(),
                        cmd.getAmount(),
                        cmd.getCurrency(),
                        cmd.getBillingMode(),
                        cmd.getIdempotencyKey(),
                        order.getVersion())));

        // ── Start saga ─────────────────────────────────────────────────
        PlaceOrderSagaData sagaData = new PlaceOrderSagaData(
                orderId,
                cmd.getSubscriberId(),
                cmd.getOfferCode(),
                cmd.getAmount(),
                cmd.getCurrency(),
                cmd.getBillingMode());
        sagaInstanceFactory.create(placeOrderSaga, sagaData);
        log.info("PlaceOrderSaga started: orderId={}", orderId);

        // ── Store idempotency key (same tx) ────────────────────────────
        idempotencyRepo.save(new IdempotencyKey(
                cmd.getSubscriberId(), cmd.getIdempotencyKey(), orderId));

        return orderId;
    }

    /** Called by the saga's local step on successful fulfilment. */
    @Transactional
    public void confirmOrder(String orderId) {
        log.info("Confirming order: orderId={}", orderId);
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        order.confirm(Instant.now());
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderConfirmed(order.getConfirmedAt(), order.getVersion())));
    }

    /** Called by the saga when a terminal failure is reached. */
    @Transactional
    public void failOrder(String orderId, String failedStep, String reason) {
        log.warn("Failing order: orderId={}, failedStep={}, reason={}", orderId, failedStep, reason);
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        order.fail(failedStep, reason);
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderFailed(failedStep, reason, order.getVersion())));
    }
}
