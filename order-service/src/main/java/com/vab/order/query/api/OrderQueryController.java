package com.vab.order.query.api;

import com.vab.order.query.document.OrderView;
import com.vab.order.query.repository.OrderViewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/orders")
public class OrderQueryController {

    private static final Logger log = LoggerFactory.getLogger(OrderQueryController.class);

    private final OrderViewRepository repo;

    public OrderQueryController(OrderViewRepository repo) {
        this.repo = repo;
    }

    /** GET /v1/orders/{orderId} */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderView> getOrder(@PathVariable String orderId) {
        log.info("GET /v1/orders/{}", orderId);
        return repo.findById(orderId)
                .map(view -> {
                    log.info("Found order: orderId={}, status={}", orderId, view.getStatus());
                    return ResponseEntity.ok(view);
                })
                .orElseGet(() -> {
                    log.info("Order not found: orderId={}", orderId);
                    return ResponseEntity.notFound().build();
                });
    }

    /** GET /v1/orders?subscriberId=... */
    @GetMapping
    public List<OrderView> listOrders(@RequestParam String subscriberId) {
        log.info("GET /v1/orders?subscriberId={}", subscriberId);
        List<OrderView> orders = repo.findBySubscriberIdOrderByPlacedAtDesc(subscriberId);
        log.info("Found {} order(s) for subscriberId={}", orders.size(), subscriberId);
        return orders;
    }
}
