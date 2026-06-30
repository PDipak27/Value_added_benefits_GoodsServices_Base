package com.vab.order.query.repository;

import com.vab.order.query.document.OrderSearchView;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for the order_search_v1 ops projection. The flexible, multi-filter
 * ops query is built dynamically in {@code OrderSearchService} via MongoTemplate;
 * this interface covers the projector's upsert (save / findById).
 */
public interface OrderSearchViewRepository extends MongoRepository<OrderSearchView, String> {
}
