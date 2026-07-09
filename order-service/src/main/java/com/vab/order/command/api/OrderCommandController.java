package com.vab.order.command.api;

import com.vab.order.command.domain.Order;
import com.vab.order.command.domain.OrderRepository;
import com.vab.order.command.domain.PlaceOrderCommand;
import com.vab.order.command.service.OrderCommandService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
public class OrderCommandController {

    private final OrderCommandService commandService;
    private final OrderRepository     orderRepo;

    public OrderCommandController(OrderCommandService commandService, OrderRepository orderRepo) {
        this.commandService = commandService;
        this.orderRepo      = orderRepo;
    }

    /**
     * POST /v1/orders
     * Header: Idempotency-Key: <UUIDv4>   (required)
     *
     * Returns: 202 Accepted + Location: /v1/orders/{orderId}
     */
    @PostMapping
    public ResponseEntity<PlaceOrderResponse> placeOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody PlaceOrderRequest request,
            UriComponentsBuilder ucb) {

        validateIdempotencyKey(idempotencyKey);

        // §A-3/A5: the subject is the authenticated subscriber (JWT claim), never client input.
        String subscriberId = subscriberId(jwt);

        String orderId = commandService.placeOrder(new PlaceOrderCommand(
                subscriberId,
                request.offerCode(),
                request.productType(),
                request.priceSnapshotId(),
                request.amount(),
                request.currency(),
                request.billingMode(),
                idempotencyKey
        ));

        var location = ucb.path("/v1/orders/{id}").buildAndExpand(orderId).toUri();
        return ResponseEntity.accepted()
                .location(location)
                .body(new PlaceOrderResponse(orderId));
    }

    /**
     * POST /v1/orders/{id}/cancel
     *
     * <p>Cooperative, best-effort cancel (DD-26): flags the order and returns 202.
     * The saga resolves the actual outcome at its next checkpoint — rollback to
     * CANCELLED before the pivot, or forward-recovery to CANCELLED_REFUNDED in the
     * pre-fulfil window. Returns 409 once the order is terminal (incl. COMPLETED):
     * after fulfilment, cancel is refused.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelOrder(@PathVariable("id") String orderId,
                                            @AuthenticationPrincipal Jwt jwt) {
        authorizeOwnership(orderId, jwt);   // 404 if not the owner (and not admin)
        try {
            commandService.requestCancel(orderId);
            return ResponseEntity.accepted().build();
        } catch (IllegalStateException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * POST /v1/orders/{id}/retry-fulfilment   (admin, DD-27)
     *
     * <p>Re-drives a parked order by re-sending the fulfil command to
     * fulfilment-service. Returns 202: the order completes (or re-parks)
     * asynchronously when fulfilment replies. 409 if the order is not currently
     * {@code FULFILMENT_FAILED}.
     */
    @PostMapping("/{id}/retry-fulfilment")
    public ResponseEntity<Void> retryFulfilment(@PathVariable("id") String orderId) {
        try {
            commandService.retryFulfilment(orderId);
            return ResponseEntity.accepted().build();
        } catch (IllegalStateException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * POST /v1/orders/{id}/complete-fulfilment   (admin, DD-27)
     * Body: { "externalRef": "OTT-..." }
     *
     * <p>Manual override: completes a parked order with an out-of-band entitlement
     * ref, without re-calling OTT. 409 if the order is not currently parked.
     */
    @PostMapping("/{id}/complete-fulfilment")
    public ResponseEntity<Void> completeFulfilment(@PathVariable("id") String orderId,
                                                   @RequestBody CompleteFulfilmentRequest request) {
        try {
            commandService.completeFulfilment(orderId, request.externalRef());
            return ResponseEntity.accepted().build();
        } catch (IllegalStateException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * POST /v1/orders/{id}/revoke-entitlement   (admin, Phase 3)
     *
     * <p>Revokes a completed order's entitlement (OTT {@code DELETE} for
     * DIGITAL_SUBSCRIPTION; read-model-only for SOFTWARE_LICENSE). Returns 202: the
     * entitlement flips to REVOKED asynchronously when fulfilment replies. 409 if the
     * order is not COMPLETED or has no entitlement to revoke.
     */
    @PostMapping("/{id}/revoke-entitlement")
    public ResponseEntity<Void> revokeEntitlement(@PathVariable("id") String orderId) {
        try {
            commandService.requestEntitlementRevoke(orderId);
            return ResponseEntity.accepted().build();
        } catch (IllegalStateException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ── Request / Response DTOs (inner classes for skeleton brevity) ──────

    public record PlaceOrderRequest(
            String offerCode,
            String productType,
            String priceSnapshotId,
            long   amount,
            String currency,
            String billingMode
    ) {}

    public record PlaceOrderResponse(String orderId) {}

    public record CompleteFulfilmentRequest(String externalRef) {}

    // ── Identity / validation ───────────────────────────────────────────────

    /** The authenticated subscriber, from the JWT {@code subscriberId} claim. */
    public static String subscriberId(Jwt jwt) {
        String sid = jwt == null ? null : jwt.getClaimAsString("subscriberId");
        if (sid == null || sid.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token carries no subscriberId claim");
        }
        return sid;
    }

    /** Whether the token carries the back-office {@code vab-admin} realm role (sees any order). */
    public static boolean isAdmin(Jwt jwt) {
        Object realmAccess = jwt == null ? null : jwt.getClaim("realm_access");
        return realmAccess instanceof Map<?, ?> ra
                && ra.get("roles") instanceof List<?> roles
                && roles.contains("vab-admin");
    }

    /**
     * Object-level authorization (§A-3 hardening): a subscriber may only act on their
     * own order; admins may act on any. A non-owner gets {@code 404} — never {@code 403} —
     * so the endpoint does not reveal whether the order exists.
     */
    private void authorizeOwnership(String orderId, Jwt jwt) {
        if (isAdmin(jwt)) return;
        String caller = subscriberId(jwt);
        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null || !caller.equals(order.getSubscriberId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Idempotency-Key header is required");
        }
        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be a valid UUID v4");
        }
    }
}
