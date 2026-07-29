package com.vab.order.command.api;

import com.vab.order.command.domain.PlaceOrderCommand;
import com.vab.order.command.service.OrderCommandService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

/**
 * LITE order command API.
 *
 * <p>Auth is disabled in Lite (no Keycloak / OAuth2). The full build derived the
 * subject from the gateway-relayed JWT {@code subscriberId} claim; Lite instead
 * takes it from an {@code X-Subscriber-Id} header (default {@code sub_demo}) so the
 * happy-path e2e can place orders without a token. The admin-only endpoints
 * (retry / complete / revoke) are removed with the fulfilment/entitlement flows.
 * See Design/lite-cutlist.md.
 */
@RestController
@RequestMapping("/v1/orders")
public class OrderCommandController {

    private static final String DEFAULT_SUBSCRIBER = "sub_demo";

    private final OrderCommandService commandService;

    public OrderCommandController(OrderCommandService commandService) {
        this.commandService = commandService;
    }

    /**
     * POST /v1/orders
     * Header: Idempotency-Key: <UUIDv4>   (required)
     * Header: X-Subscriber-Id: <id>       (optional; defaults to sub_demo in Lite)
     *
     * Returns: 202 Accepted + Location: /v1/orders/{orderId}
     */
    @PostMapping
    public ResponseEntity<PlaceOrderResponse> placeOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Subscriber-Id", defaultValue = DEFAULT_SUBSCRIBER) String subscriberId,
            @RequestBody PlaceOrderRequest request,
            UriComponentsBuilder ucb) {

        validateIdempotencyKey(idempotencyKey);

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
     * The saga resolves the actual outcome at its next checkpoint. Returns 409 once
     * the order is terminal (incl. COMPLETED).
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelOrder(@PathVariable("id") String orderId) {
        try {
            commandService.requestCancel(orderId);
            return ResponseEntity.accepted().build();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ── Request / Response DTOs ───────────────────────────────────────────

    public record PlaceOrderRequest(
            String offerCode,
            String productType,
            String priceSnapshotId,
            long   amount,
            String currency,
            String billingMode
    ) {}

    public record PlaceOrderResponse(String orderId) {}

    // ── Validation ────────────────────────────────────────────────────────

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key header is required");
        }
        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be a valid UUID v4");
        }
    }
}
