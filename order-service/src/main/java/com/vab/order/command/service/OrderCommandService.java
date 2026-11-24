package com.vab.order.command.service;

import com.vab.events.billing.CancelPaymentCommand;
import com.vab.events.order.OrderAwaitingPayment;
import com.vab.events.order.OrderCancelled;
import com.vab.events.order.OrderCancelledRefunded;
import com.vab.events.order.OrderCompleted;
import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderEntitlementRevoked;
import com.vab.events.order.OrderFailed;
import com.vab.events.order.OrderPlaced;
import com.vab.events.fulfilment.CancelProvisioningCommand;
import com.vab.order.command.catalog.CatalogClient;
import com.vab.order.command.domain.Order;
import com.vab.order.command.domain.OrderRepository;
import com.vab.order.command.domain.OrderStatus;
import com.vab.order.command.domain.PlaceOrderCommand;
import com.vab.order.command.fulfilment.EntitlementRevoke;
import com.vab.order.idempotency.IdempotencyKey;
import com.vab.order.idempotency.IdempotencyKeyRepository;
import com.vab.order.query.repository.EntitlementViewRepository;
import com.vab.order.saga.PlaceOrderSaga;
import com.vab.order.saga.PlaceOrderSagaData;
import io.eventuate.tram.commands.producer.CommandProducer;
import io.eventuate.tram.events.publisher.DomainEventPublisher;
import io.eventuate.tram.sagas.orchestration.SagaInstanceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Write side of the Order aggregate (DD-14).
 *
 * <p>The aggregate is state-stored via JPA; domain events are published through
 * the Eventuate Tram transactional outbox in the <em>same</em> JDBC transaction
 * as the state change, giving the atomic "write + publish" guarantee with no
 * dual write. Eventuate CDC relays the outbox to Kafka.
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
    private final CommandProducer          commandProducer;
    private final EntitlementViewRepository entitlementRepo;
    private final EntitlementRevoke        entitlementRevoke;

    public OrderCommandService(OrderRepository orderRepo,
                               DomainEventPublisher domainEventPublisher,
                               SagaInstanceFactory sagaInstanceFactory,
                               PlaceOrderSaga placeOrderSaga,
                               IdempotencyKeyRepository idempotencyRepo,
                               CatalogClient catalogClient,
                               CommandProducer commandProducer,
                               EntitlementViewRepository entitlementRepo,
                               EntitlementRevoke entitlementRevoke) {
        this.orderRepo            = orderRepo;
        this.domainEventPublisher = domainEventPublisher;
        this.sagaInstanceFactory  = sagaInstanceFactory;
        this.placeOrderSaga       = placeOrderSaga;
        this.idempotencyRepo      = idempotencyRepo;
        this.catalogClient        = catalogClient;
        this.commandProducer      = commandProducer;
        this.entitlementRepo      = entitlementRepo;
        this.entitlementRevoke    = entitlementRevoke;
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

        // ── Uniqueness defense layer 1: one active entitlement per offer ──
        // Reject a re-purchase of an offer the subscriber is already entitled to.
        // Eventually-consistent read, so a double-tap within projection lag can
        // slip past here — the Mongo partial unique index is the safety net.
        if (entitlementRepo.existsBySubscriberIdAndOfferCodeAndStatus(
                cmd.getSubscriberId(), cmd.getOfferCode(), "ACTIVE")) {
            log.warn("Duplicate entitlement rejected: subscriberId={}, offerCode={}",
                    cmd.getSubscriberId(), cmd.getOfferCode());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Already entitled to offer " + cmd.getOfferCode());
        }

        log.info("Placing order: subscriberId={}, offerCode={}, amount={} {}",
                cmd.getSubscriberId(), cmd.getOfferCode(), cmd.getAmount(), cmd.getCurrency());

        // ── Verify against catalog: price (fail-closed), productType, term ──
        // The mobile app builds the order from an offer and sends its price; catalog is
        // authoritative. The amount/currency must match the current catalog price (a
        // stale or tampered price is a 409), an unknown/withdrawn offer is a 422, and an
        // unreachable catalog is a 503 — price integrity is fail-closed (DD-32).
        // productType and termMonths (drive the entitlement's validUntil) come from catalog.
        CatalogClient.OfferDetail offer;
        try {
            offer = catalogClient.resolveOffer(cmd.getOfferCode());
        } catch (CatalogClient.CatalogUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Catalog unavailable: the price of " + cmd.getOfferCode() + " cannot be verified, retry later");
        }
        if (offer == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unknown or withdrawn offer " + cmd.getOfferCode());
        }
        if (offer.amount() == null || offer.amount() != cmd.getAmount()
                || !Objects.equals(offer.currency(), cmd.getCurrency())) {
            log.warn("Price mismatch for offerCode={}: client={} {}, catalog={} {}", cmd.getOfferCode(),
                    cmd.getAmount(), cmd.getCurrency(), offer.amount(), offer.currency());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Price mismatch for offer " + cmd.getOfferCode()
                    + ": current price is " + offer.amount() + " " + offer.currency());
        }
        String productType = cmd.getProductType();
        if (offer.productType() != null && !offer.productType().equals(productType)) {
            log.warn("ProductType mismatch for offerCode={}: client={}, catalog={} — using catalog",
                    cmd.getOfferCode(), productType, offer.productType());
            productType = offer.productType();
        }
        Integer termMonths      = offer.termMonths();
        String  priceSnapshotId = offer.priceSnapshotId() != null ? offer.priceSnapshotId() : cmd.getPriceSnapshotId();

        // ── Insert state-stored aggregate ──────────────────────────────
        String orderId = "ord_" + UUID.randomUUID().toString().replace("-", "");
        Order order = Order.place(
                orderId,
                cmd.getSubscriberId(),
                cmd.getOfferCode(),
                productType,
                priceSnapshotId,
                cmd.getAmount(),
                cmd.getCurrency(),
                cmd.getBillingMode());
        order.setTermMonths(termMonths);   // snapshot for a possible re-drive
        orderRepo.saveAndFlush(order);   // assigns @Version (0)
        log.info("Order persisted (PLACED): orderId={}, productType={}, version={}",
                orderId, productType, order.getVersion());

        // ── Publish OrderPlaced via the Tram outbox (same tx) ──────────
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderPlaced(
                        cmd.getSubscriberId(),
                        cmd.getOfferCode(),
                        productType,
                        priceSnapshotId,
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
                cmd.getBillingMode(),
                termMonths);
        sagaInstanceFactory.create(placeOrderSaga, sagaData);
        log.info("PlaceOrderSaga started: orderId={}", orderId);

        // ── Store idempotency key (same tx) ────────────────────────────
        idempotencyRepo.save(new IdempotencyKey(
                cmd.getSubscriberId(), cmd.getIdempotencyKey(), orderId));

        return orderId;
    }

    /**
     * PAY_NOW (Design/15), from the saga once billing has a payment intent: the order waits in
     * PENDING_PAYMENT and the subscriber pays at {@code checkoutUrl}. Publishes {@code OrderAwaitingPayment}.
     */
    @Transactional
    public void awaitPayment(String orderId, String checkoutUrl, Instant expiresAt) {
        log.info("Awaiting payment: orderId={}, expiresAt={}", orderId, expiresAt);
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        order.awaitPayment(checkoutUrl, expiresAt);
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderAwaitingPayment(checkoutUrl, expiresAt, order.getVersion())));
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
        completeOrder(orderId, productType, trackingRef, activationKey, externalRef, null, null);
    }

    /** Complete with the benefit validity window (DIGITAL_SUBSCRIPTION / SOFTWARE_LICENSE). */
    @Transactional
    public void completeOrder(String orderId, String productType, String trackingRef,
                              String activationKey, String externalRef,
                              Instant validFrom, Instant validUntil) {
        log.info("Completing order: orderId={}, productType={}, validUntil={}", orderId, productType, validUntil);
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        order.complete(Instant.now(), trackingRef, activationKey, externalRef, validFrom, validUntil);
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderCompleted(order.getCompletedAt(), order.getVersion(),
                        productType, trackingRef, activationKey, externalRef, validFrom, validUntil)));
    }

    /**
     * User-initiated cancel request (DD-26). Cooperative: this flags the order and the
     * running saga decides the outcome at its next checkpoint. Rejected
     * (IllegalStateException → 409) once the order is terminal — including
     * COMPLETED, so "after fulfilment, cancel is refused". Idempotent.
     *
     * <p>Design/13 §6: the row is locked ({@code SELECT … FOR UPDATE}), so a concurrent saga
     * step loses its {@code @Version} check and is retried (it then sees the flag). For a
     * DIGITAL_SUBSCRIPTION past the saga's pre-fulfil checkpoint, provisioning may be waiting
     * for OTT, so fulfilment is told in the same transaction ({@link CancelProvisioningCommand});
     * its poller answers the waiting saga with a cancelled reply → refund.
     */
    @Transactional
    public void requestCancel(String orderId) {
        log.info("Cancel requested: orderId={}", orderId);
        Order order = orderRepo.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        order.requestCancel();           // throws if terminal
        orderRepo.saveAndFlush(order);
        if (order.isPaymentPending()) {
            // Design/15: the saga may be waiting for the subscriber to pay; billing closes the intent and answers it.
            commandProducer.send(PlaceOrderSaga.BILLING_CHANNEL,
                    new CancelPaymentCommand(orderId, "USER_CANCEL: before payment"),
                    PAYMENT_CONTROL_REPLY_CHANNEL, Map.of());
            log.info("Cancel forwarded to billing (payment may be pending): orderId={}", orderId);
        }
        if (order.isWaitingOnProvisioning()) {
            commandProducer.send(PlaceOrderSaga.FULFILMENT_CHANNEL,
                    new CancelProvisioningCommand(orderId, "USER_CANCEL: during provisioning"),
                    PROVISIONING_CONTROL_REPLY_CHANNEL, Map.of());
            log.info("Cancel forwarded to fulfilment (provisioning may be in progress): orderId={}", orderId);
        }
    }

    /** Required by Tram's command API; billing never replies to {@link CancelPaymentCommand}. */
    static final String PAYMENT_CONTROL_REPLY_CHANNEL = "orderService-payment-control";

    /** Required by Tram's command API; fulfilment never replies to {@link CancelProvisioningCommand}. */
    static final String PROVISIONING_CONTROL_REPLY_CHANNEL = "orderService-provisioning-control";

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
     * Admin revoke of a completed order's entitlement (Phase 3 / backlog A4). Validates
     * the order is COMPLETED and a benefit type, then sends the revoke command; the
     * entitlement is marked revoked asynchronously when fulfilment replies. The order
     * stays COMPLETED (revoke is not a refund).
     *
     * @throws IllegalStateException (→ 409) if not COMPLETED or not a benefit type
     */
    @Transactional
    public void requestEntitlementRevoke(String orderId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new IllegalStateException("Order " + orderId + " is " + order.getStatus()
                    + "; entitlement revoke requires COMPLETED");
        }
        if (!isBenefitType(order.getProductType())) {
            throw new IllegalStateException("Order " + orderId + " productType "
                    + order.getProductType() + " has no entitlement to revoke");
        }
        log.info("Entitlement revoke requested: orderId={}, productType={}", orderId, order.getProductType());
        entitlementRevoke.revoke(order);
    }

    /**
     * Applies a successful revoke reply: marks the order's entitlement revoked and
     * publishes {@code OrderEntitlementRevoked} (the projector flips the read model to
     * REVOKED, freeing the uniqueness slot). Idempotent.
     */
    @Transactional
    public void applyEntitlementRevoked(String orderId) {
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        if (order.isEntitlementRevoked()) {
            log.info("Entitlement revoke already applied: orderId={}", orderId);
            return;
        }
        order.revokeEntitlement(Instant.now());
        orderRepo.saveAndFlush(order);   // bumps @Version
        domainEventPublisher.publish(Order.AGGREGATE_TYPE, orderId, List.of(
                new OrderEntitlementRevoked(order.getEntitlementRevokedAt(), order.getVersion())));
    }

    private static boolean isBenefitType(String productType) {
        return "DIGITAL_SUBSCRIPTION".equals(productType) || "SOFTWARE_LICENSE".equals(productType);
    }
}
