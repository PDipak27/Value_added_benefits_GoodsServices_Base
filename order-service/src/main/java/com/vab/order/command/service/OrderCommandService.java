package com.vab.order.command.service;

import com.vab.events.order.OrderCancelled;
import com.vab.events.order.OrderCancelledRefunded;
import com.vab.events.order.OrderCompleted;
import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderFailed;
import com.vab.events.order.OrderFulfilmentFailed;
import com.vab.events.order.OrderPlaced;
import com.vab.order.command.catalog.CatalogClient;
import com.vab.order.command.domain.Order;
import com.vab.order.command.domain.OrderRepository;
import com.vab.order.command.domain.OrderStatus;
import com.vab.order.command.domain.PlaceOrderCommand;
import com.vab.order.command.fulfilment.FulfilmentReDrive;
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
    private final CatalogClient            catalogClient;
    private final FulfilmentReDrive        fulfilmentReDrive;

    public OrderCommandService(OrderRepository orderRepo,
                               DomainEventPublisher domainEventPublisher,
                               SagaInstanceFactory sagaInstanceFactory,
                               PlaceOrderSaga placeOrderSaga,
                               IdempotencyKeyRepository idempotencyRepo,
                               CatalogClient catalogClient,
                               FulfilmentReDrive fulfilmentReDrive) {
        this.orderRepo            = orderRepo;
        this.domainEventPublisher = domainEventPublisher;
        this.sagaInstanceFactory  = sagaInstanceFactory;
        this.placeOrderSaga       = placeOrderSaga;
        this.idempotencyRepo      = idempotencyRepo;
        this.catalogClient        = catalogClient;
        this.fulfilmentReDrive    = fulfilmentReDrive;
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

        // ── Resolve productType (catalog-verified, fail-open) ──────────
        // The mobile app builds the order from an offer, so it sends a productType.
        // We verify it against catalog when reachable; on any failure we keep the
        // client-sent value (DD-20 fail-open). Catalog is authoritative when present.
        String productType = cmd.getProductType();
        String verified    = catalogClient.resolveProductType(cmd.getOfferCode());
        if (verified != null && !verified.equals(productType)) {
            log.warn("ProductType mismatch for offerCode={}: client={}, catalog={} — using catalog",
                    cmd.getOfferCode(), productType, verified);
        }
        if (verified != null) {
            productType = verified;
        }

        // ── Insert state-stored aggregate ──────────────────────────────
        String orderId = "ord_" + UUID.randomUUID().toString().replace("-", "");
        Order order = Order.place(
                orderId,
                cmd.getSubscriberId(),
                cmd.getOfferCode(),
                productType,
                cmd.getPriceSnapshotId(),
                cmd.getAmount(),
                cmd.getCurrency(),
                cmd.getBillingMode());
        orderRepo.saveAndFlush(order);   // assigns @Version (0)
        log.info("Order persisted (PLACED): orderId={}, productType={}, version={}",
                orderId, productType, order.getVersion());

        // ── Publish OrderPlaced via the Tram outbox (same tx) ──────────
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderPlaced(
                        cmd.getSubscriberId(),
                        cmd.getOfferCode(),
                        productType,
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
                productType,
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

    /**
     * Saga local step after inventory is settled (PAY_NOW: authorized + committed;
     * BILL_TO_MOBILE: allocated + billed). Intermediate state before fulfilment;
     * the delivery artifact is not known yet, so it ships on {@code OrderCompleted}.
     */
    @Transactional
    public void confirmOrder(String orderId, String productType) {
        log.info("Confirming order: orderId={}, productType={}", orderId, productType);
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        order.confirm(Instant.now());
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderConfirmed(order.getConfirmedAt(), order.getVersion(), productType)));
    }

    /**
     * Saga local step after fulfilment and (PAY_NOW) capture. Terminal success;
     * carries the delivery artifact (exactly one of trackingRef / activationKey /
     * externalRef) so the read model and notification render without a re-lookup.
     */
    @Transactional
    public void completeOrder(String orderId, String productType, String trackingRef,
                              String activationKey, String externalRef) {
        log.info("Completing order: orderId={}, productType={}", orderId, productType);
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        order.complete(Instant.now(), trackingRef, activationKey, externalRef);
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderCompleted(order.getCompletedAt(), order.getVersion(),
                        productType, trackingRef, activationKey, externalRef)));
    }

    /**
     * User-initiated cancel request (DD-26). Cooperative: this only flags the
     * order; the running saga decides the outcome at its next checkpoint. Rejected
     * (IllegalStateException → 409) once the order is terminal — including
     * COMPLETED, so "after fulfilment, cancel is refused". Best-effort and
     * idempotent: re-flagging a still-cancellable order is a no-op-ish re-set.
     */
    @Transactional
    public void requestCancel(String orderId) {
        log.info("Cancel requested: orderId={}", orderId);
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        order.requestCancel();           // throws if terminal
        orderRepo.saveAndFlush(order);
    }

    /** Read the cooperative cancel flag — called by the saga's cancel checkpoints. */
    @Transactional(readOnly = true)
    public boolean isCancelRequested(String orderId) {
        return orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId))
                .isCancelRequested();
    }

    /**
     * Saga checkpoint before the pivot found a pending cancel (DD-26). Marks the
     * order CANCELLED and publishes {@code OrderCancelled}; the saga then rolls
     * back the prior compensatable steps. Nothing was charged.
     */
    @Transactional
    public void cancel(String orderId, String reason) {
        log.info("Cancelling order (pre-pivot): orderId={}, reason={}", orderId, reason);
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        order.cancel(reason);
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderCancelled(order.getCancelledAt(), order.getVersion(), reason)));
    }

    /**
     * Saga forward-recovery terminal step (DD-26): the charge was settled and has
     * been refunded (PAY_NOW) or reversed on the next-cycle ledger (BILL_TO_MOBILE)
     * and inventory released. Marks the order CANCELLED_REFUNDED and publishes
     * {@code OrderCancelledRefunded}.
     */
    @Transactional
    public void cancelRefunded(String orderId, String reason) {
        log.info("Cancelling order (post-pivot, refunded): orderId={}, reason={}", orderId, reason);
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        order.cancelRefunded(reason);
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderCancelledRefunded(order.getCancelledAt(), order.getVersion(), reason)));
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

    /**
     * Saga finalize branch (DD-27): OTT provisioning failed (success-outcome
     * {@code OrderProvisioningFailed} reply). The charge stands — no refund. The
     * order is parked in the non-terminal {@code FULFILMENT_FAILED} state and
     * {@code OrderFulfilmentFailed} is published so notification alerts an admin.
     */
    @Transactional
    public void fulfilmentFailed(String orderId, String reason) {
        log.warn("Parking order (FULFILMENT_FAILED): orderId={}, reason={}", orderId, reason);
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        order.fulfilmentFailed(reason);
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderFulfilmentFailed(order.getLastAttemptAt(), order.getVersion(),
                        order.getFailedStep(), reason)));
    }

    /**
     * Admin re-drive of a parked order (DD-27): re-send the original
     * {@code FulfilOrderCommand} so fulfilment-service's {@code fulfil()} handler
     * runs again against the now-fixed OTT service. This is a plain Tram command
     * (not a saga, not a saga resume — the original saga has resolved). The send
     * joins this transaction via the Tram outbox; the order stays parked until the
     * reply lands and {@link #completeFromReDrive}/{@link #parkFromReDrive} applies it.
     *
     * @throws IllegalStateException (→ 409) if the order is not currently parked
     */
    @Transactional
    public void retryFulfilment(String orderId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        requireParked(order);
        log.info("Re-driving fulfilment (Tram command): orderId={}, productType={}",
                orderId, order.getProductType());
        fulfilmentReDrive.reDrive(order);
    }

    /**
     * Applies a successful re-drive reply (DD-27): completes the parked order with the
     * provisioned {@code externalRef}. Idempotent — a reply for an order that is no
     * longer parked (already completed by an earlier delivery) is a logged no-op.
     */
    @Transactional
    public void completeFromReDrive(String orderId, String externalRef) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        if (order.getStatus() != OrderStatus.FULFILMENT_FAILED) {
            log.info("Re-drive success ignored: orderId={} is {}, not parked", orderId, order.getStatus());
            return;
        }
        order.complete(Instant.now(), null, null, externalRef);
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderCompleted(order.getCompletedAt(), order.getVersion(),
                        order.getProductType(), null, null, externalRef)));
    }

    /**
     * Applies a failed re-drive reply (DD-27): the order stays parked. Re-stamps the
     * attempt and re-publishes {@code OrderFulfilmentFailed} so the admin is alerted
     * again. Idempotent — ignored if the order is no longer parked.
     */
    @Transactional
    public void parkFromReDrive(String orderId, String reason) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        if (order.getStatus() != OrderStatus.FULFILMENT_FAILED) {
            log.info("Re-drive failure ignored: orderId={} is {}, not parked", orderId, order.getStatus());
            return;
        }
        order.fulfilmentFailed(reason);
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderFulfilmentFailed(order.getLastAttemptAt(), order.getVersion(),
                        order.getFailedStep(), reason)));
    }

    /**
     * Admin manual override (DD-27): the entitlement was provisioned out-of-band;
     * complete the parked order with the supplied {@code externalRef} without
     * re-calling OTT.
     *
     * @throws IllegalStateException (→ 409) if the order is not currently parked
     */
    @Transactional
    public void completeFulfilment(String orderId, String externalRef) {
        log.info("Manual fulfilment override: orderId={}, externalRef={}", orderId, externalRef);
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        requireParked(order);
        order.complete(Instant.now(), null, null, externalRef);
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderCompleted(order.getCompletedAt(), order.getVersion(),
                        order.getProductType(), null, null, externalRef)));
    }

    private void requireParked(Order order) {
        if (order.getStatus() != OrderStatus.FULFILMENT_FAILED) {
            throw new IllegalStateException(
                    "Order " + order.getId() + " is " + order.getStatus() + ", not FULFILMENT_FAILED");
        }
    }
}
