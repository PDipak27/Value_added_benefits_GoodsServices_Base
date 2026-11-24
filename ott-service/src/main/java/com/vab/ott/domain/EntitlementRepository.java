package com.vab.ott.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EntitlementRepository extends JpaRepository<Entitlement, String> {
    Optional<Entitlement> findByOrderId(String orderId);

    /** PENDING entitlements old enough to activate; other activator instances skip the locked rows. */
    @Query(value = """
            SELECT * FROM ott.entitlements WHERE status = 'PENDING' AND provisioned_at <= :cutoff
             ORDER BY provisioned_at LIMIT :limit FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<Entitlement> lockPendingDue(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** §A-2 stream gating: does this subscriber hold an entitlement for the offer? */
    boolean existsBySubscriberIdAndOfferCodeAndStatus(String subscriberId, String offerCode, Entitlement.Status status);
}
