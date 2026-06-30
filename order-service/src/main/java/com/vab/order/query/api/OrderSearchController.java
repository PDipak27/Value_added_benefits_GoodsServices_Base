package com.vab.order.query.api;

import com.vab.order.query.document.OrderSearchView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Ops dashboard search (§B3): {@code GET /v1/ops/orders} with optional filters
 * (status, offerCode, placedAt from/to as ISO-8601), served from the flattened
 * {@code order_search_v1} projection. Distinct from the subscriber-facing
 * {@code /v1/orders} surface; this is the "different read shape, same events" view.
 */
@RestController
@RequestMapping("/v1/ops/orders")
public class OrderSearchController {

    private static final Logger log = LoggerFactory.getLogger(OrderSearchController.class);

    private final OrderSearchService search;

    public OrderSearchController(OrderSearchService search) {
        this.search = search;
    }

    @GetMapping
    public List<OrderSearchView> search(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String offerCode,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "100") int limit) {
        log.info("GET /v1/ops/orders status={}, offerCode={}, from={}, to={}, limit={}",
                status, offerCode, from, to, limit);
        Instant fromI = StringUtils.hasText(from) ? Instant.parse(from) : null;
        Instant toI   = StringUtils.hasText(to)   ? Instant.parse(to)   : null;
        return search.search(status, offerCode, fromI, toI, limit);
    }
}
