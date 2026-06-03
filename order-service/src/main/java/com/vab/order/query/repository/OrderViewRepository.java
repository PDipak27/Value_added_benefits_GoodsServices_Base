package com.vab.order.query.repository;

import com.vab.order.query.document.OrderView;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrderViewRepository extends MongoRepository<OrderView, String> {
    List<OrderView> findBySubscriberIdOrderByPlacedAtDesc(String subscriberId);
}
