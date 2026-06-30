package com.vab.order.query.repository;

import com.vab.order.query.document.EntitlementView;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface EntitlementViewRepository extends MongoRepository<EntitlementView, String> {

    /** Idempotent upsert key for the projector (one entitlement per source order). */
    Optional<EntitlementView> findBySourceOrderId(String sourceOrderId);

    /** "My Benefits" — the subscriber's active entitlements. */
    List<EntitlementView> findBySubscriberIdAndStatus(String subscriberId, String status);

    /** Uniqueness defense layer 1 (command-time check). */
    boolean existsBySubscriberIdAndOfferCodeAndStatus(String subscriberId, String offerCode, String status);
}
