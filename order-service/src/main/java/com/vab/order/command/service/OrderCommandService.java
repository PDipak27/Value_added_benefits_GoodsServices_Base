package com.vab.order.command.service;

import com.vab.order.command.domain.*;
import com.vab.order.idempotency.IdempotencyKey;
import com.vab.order.idempotency.IdempotencyKeyRepository;
import com.vab.order.saga.PlaceOrderSaga;
import com.vab.order.saga.PlaceOrderSagaData;
import io.eventuate.sync.EventuateAggregateStore;
import io.eventuate.tram.sagas.orchestration.SagaInstanceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.Optional;

@Service
public class OrderCommandService {

    private static final Logger log = LoggerFactory.getLogger(OrderCommandService.class);

    private final EventuateAggregateStore    aggregateStore;
    private final SagaInstanceFactory        sagaInstanceFactory;
    private final PlaceOrderSaga             placeOrderSaga;
    private final IdempotencyKeyRepository   idempotencyRepo;

    public OrderCommandService(EventuateAggregateStore aggregateStore,
                               SagaInstanceFactory sagaInstanceFactory,
                               PlaceOrderSaga placeOrderSaga,
                               IdempotencyKeyRepository idempotencyRepo) {
        this.aggregateStore     = aggregateStore;
        this.sagaInstanceFactory = sagaInstanceFactory;
        this.placeOrderSaga     = placeOrderSaga;
        this.idempotencyRepo    = idempotencyRepo;
    }

    /**
     * Places an order.
     *
     * Idempotency: (subscriberId, idempotencyKey) lookup first.
     * If hit → return stored orderId (no side effects).
     * If miss → create aggregate + start Saga + store key (atomic in one transaction).
     *
     * @return orderId (either new or previously stored)
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

        // ── Create Order aggregate ─────────────────────────────────────
        // Eventuate ES: save(entity class, command) → applies process() → persists events
        var entityId = aggregateStore.save(
                Order.class,
                Collections.singletonList(
                        new com.vab.events.order.OrderPlaced(
                                cmd.getSubscriberId(),
                                cmd.getOfferCode(),
                                cmd.getPriceSnapshotId(),
                                cmd.getAmount(),
                                cmd.getCurrency(),
                                cmd.getBillingMode(),
                                cmd.getIdempotencyKey()
                        )
                ),
                Optional.empty()
        );

        String orderId = entityId.getEntityId();
        log.info("Order aggregate persisted (OrderPlaced): orderId={}", orderId);

        // ── Start Saga ────────────────────────────────────────────────
        PlaceOrderSagaData sagaData = new PlaceOrderSagaData(
                orderId,
                cmd.getSubscriberId(),
                cmd.getOfferCode(),
                cmd.getAmount(),
                cmd.getCurrency(),
                cmd.getBillingMode()
        );
        sagaInstanceFactory.create(placeOrderSaga, sagaData);
        log.info("PlaceOrderSaga started: orderId={}", orderId);

        // ── Store idempotency key (same transaction) ──────────────────
        idempotencyRepo.save(new IdempotencyKey(
                cmd.getSubscriberId(), cmd.getIdempotencyKey(), orderId));

        return orderId;
    }

    /**
     * Called by the Saga's local step on successful fulfilment.
     */
    @Transactional
    public void confirmOrder(String orderId) {
        log.info("Confirming order (OrderConfirmed): orderId={}", orderId);
        aggregateStore.update(
                Order.class,
                orderId,
                Collections.singletonList(
                        new com.vab.events.order.OrderConfirmed(java.time.Instant.now())
                ),
                Optional.empty()
        );
    }

    /**
     * Called by the Saga when a terminal failure is reached.
     */
    @Transactional
    public void failOrder(String orderId, String failedStep, String reason) {
        log.warn("Failing order (OrderFailed): orderId={}, failedStep={}, reason={}", orderId, failedStep, reason);
        aggregateStore.update(
                Order.class,
                orderId,
                Collections.singletonList(
                        new com.vab.events.order.OrderFailed(failedStep, reason)
                ),
                Optional.empty()
        );
    }
}
