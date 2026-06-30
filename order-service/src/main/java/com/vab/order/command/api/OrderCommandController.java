package com.vab.order.command.api;

import com.vab.order.command.domain.PlaceOrderCommand;
import com.vab.order.command.service.OrderCommandService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
public class OrderCommandController {

    private final OrderCommandService commandService;

    public OrderCommandController(OrderCommandService commandService) {
        this.commandService = commandService;
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
            @RequestBody PlaceOrderRequest request,
            UriComponentsBuilder ucb) {

        validateIdempotencyKey(idempotencyKey);

        // subscriberId stubbed from header for skeleton; replaced by JWT claim in iteration 6
        String subscriberId = request.subscriberId();

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
    public ResponseEntity<Void> cancelOrder(@PathVariable("id") String orderId) {
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
            String subscriberId,
            String offerCode,
            String productType,
            String priceSnapshotId,
            long   amount,
            String currency,
            String billingMode
    ) {}

    public record PlaceOrderResponse(String orderId) {}

    public record CompleteFulfilmentRequest(String externalRef) {}

    // ── Validation ────────────────────────────────────────────────────────

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
