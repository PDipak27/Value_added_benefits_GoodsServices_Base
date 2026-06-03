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

    // ── Request / Response DTOs (inner classes for skeleton brevity) ──────

    public record PlaceOrderRequest(
            String subscriberId,
            String offerCode,
            String priceSnapshotId,
            long   amount,
            String currency,
            String billingMode
    ) {}

    public record PlaceOrderResponse(String orderId) {}

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
