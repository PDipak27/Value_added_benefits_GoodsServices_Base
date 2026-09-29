package com.vab.order.query.api;

import com.vab.order.command.domain.Order;
import com.vab.order.command.domain.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * LITE read side — the Mongo CQRS projection is removed; order status is read
 * straight from the Postgres write model ({@link OrderRepository}). This is a
 * strongly-consistent single-key point read (no projection lag), which is all the
 * happy-path e2e needs to poll for COMPLETED. See Design/lite-cutlist.md.
 */
@RestController
@RequestMapping("/v1/orders")
public class LiteOrderQueryController {

    private final OrderRepository orderRepo;

    public LiteOrderQueryController(OrderRepository orderRepo) {
        this.orderRepo = orderRepo;
    }

    /** GET /v1/orders/{id} — current order state from the write model. */
    @GetMapping("/{id}")
    public OrderView get(@PathVariable("id") String orderId) {
        Order o = orderRepo.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        return new OrderView(
                o.getId(),
                o.getSubscriberId(),
                o.getOfferCode(),
                o.getProductType(),
                o.getStatus().name(),
                o.getAmount(),
                o.getCurrency(),
                o.getBillingMode(),
                o.getTrackingRef(),
                o.getActivationKey(),
                o.getExternalRef(),
                o.getPlacedAt(),
                o.getCompletedAt());
    }

    public record OrderView(
            String orderId,
            String subscriberId,
            String offerCode,
            String productType,
            String status,
            long   amount,
            String currency,
            String billingMode,
            String trackingRef,
            String activationKey,
            String externalRef,
            Instant placedAt,
            Instant completedAt
    ) {}
}
