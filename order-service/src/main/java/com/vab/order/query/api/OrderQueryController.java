package com.vab.order.query.api;

import com.vab.order.command.domain.Order;
import com.vab.order.command.domain.OrderRepository;
import com.vab.order.query.document.OrderView;
import com.vab.order.query.repository.OrderViewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.vab.order.command.api.OrderCommandController.isAdmin;
import static com.vab.order.command.api.OrderCommandController.subscriberId;

@RestController
@RequestMapping("/v1/orders")
public class OrderQueryController {

    private static final Logger log = LoggerFactory.getLogger(OrderQueryController.class);

    private final OrderViewRepository repo;
    private final OrderRepository     orderRepo;

    public OrderQueryController(OrderViewRepository repo, OrderRepository orderRepo) {
        this.repo      = repo;
        this.orderRepo = orderRepo;
    }

    /**
     * GET /v1/orders/{orderId}
     *
     * <p>Primary path: the Mongo read model. On a projection miss (DD-15), fall
     * back to a bounded single-key point read of the Postgres write store so a
     * POST-then-GET never 404s during projection lag (read-your-writes). List
     * and search queries still require the read model — the CQRS boundary holds.
     *
     * <p>§A-3 hardening: object-level authorization — a subscriber sees only their
     * own order, admins see any; a non-owner gets 404 (existence is not revealed).
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderView> getOrder(@PathVariable String orderId, @AuthenticationPrincipal Jwt jwt) {
        log.info("GET /v1/orders/{}", orderId);
        boolean admin = isAdmin(jwt);
        String caller = admin ? null : subscriberId(jwt);
        return repo.findById(orderId)
                .filter(v -> admin || caller.equals(v.getSubscriberId()))
                .map(view -> {
                    log.info("Served from read model: orderId={}, status={}", orderId, view.getStatus());
                    return ResponseEntity.ok(view);
                })
                .or(() -> orderRepo.findById(orderId)
                        .filter(o -> admin || caller.equals(o.getSubscriberId()))
                        .map(order -> {
                            log.info("Read model miss — read-your-writes fallback to Postgres: orderId={}, status={}",
                                    orderId, order.getStatus());
                            return ResponseEntity.ok(toView(order));
                        }))
                .orElseGet(() -> {
                    log.info("Order not found or not owned: orderId={}", orderId);
                    return ResponseEntity.notFound().build();
                });
    }

    /** GET /v1/orders — the caller's own orders (subject from the JWT, read model only). */
    @GetMapping
    public List<OrderView> listOrders(@AuthenticationPrincipal Jwt jwt) {
        String subscriberId = subscriberId(jwt);
        log.info("GET /v1/orders (subscriberId={})", subscriberId);
        List<OrderView> orders = repo.findBySubscriberIdOrderByPlacedAtDesc(subscriberId);
        log.info("Found {} order(s) for subscriberId={}", orders.size(), subscriberId);
        return orders;
    }

    /**
     * GET /v1/orders/{orderId}/timeline (§B2) — the audit timeline only (status
     * transitions), served from the read model. 404 if the order has not projected
     * yet; the full detail (same timeline embedded) remains at GET /{orderId}.
     */
    @GetMapping("/{orderId}/timeline")
    public ResponseEntity<List<OrderView.TimelineEntry>> timeline(@PathVariable String orderId,
                                                                  @AuthenticationPrincipal Jwt jwt) {
        log.info("GET /v1/orders/{}/timeline", orderId);
        boolean admin = isAdmin(jwt);
        String caller = admin ? null : subscriberId(jwt);
        return repo.findById(orderId)
                .filter(v -> admin || caller.equals(v.getSubscriberId()))
                .map(view -> ResponseEntity.ok(view.getTimeline()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Maps the write-store aggregate into the read-model shape for the fallback path. */
    private OrderView toView(Order order) {
        OrderView view = new OrderView();
        view.setOrderId(order.getId());
        view.setSubscriberId(order.getSubscriberId());
        view.setOfferCode(order.getOfferCode());
        view.setProductType(order.getProductType());
        view.setAmount(order.getAmount());
        view.setCurrency(order.getCurrency());
        view.setStatus(order.getStatus().name());
        view.setPlacedAt(order.getPlacedAt());
        view.setConfirmedAt(order.getConfirmedAt());
        view.setCompletedAt(order.getCompletedAt());
        view.setVersion(order.getVersion());
        if (order.getCompletedAt() != null) {
            view.setFulfilment(new OrderView.Fulfilment(order.getProductType(),
                    order.getTrackingRef(), order.getActivationKey(), order.getExternalRef()));
        }
        if (order.getPlacedAt() != null) {
            view.addTimelineEntry(order.getPlacedAt(), "PLACED");
        }
        return view;
    }
}
