package com.vab.ott.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EntitlementRepository extends JpaRepository<Entitlement, String> {
    Optional<Entitlement> findByOrderId(String orderId);

    /** §A-2 stream gating: does this subscriber hold an entitlement for the offer? */
    boolean existsBySubscriberIdAndOfferCodeAndStatus(String subscriberId, String offerCode, Entitlement.Status status);
}
