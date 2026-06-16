package com.vab.inventory.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LicenseKeyRepository extends JpaRepository<LicenseKey, String> {

    /**
     * Claim the next FREE key for an offer under a pessimistic write lock, so two
     * concurrent reserves can never hand out the same activation key. Use with
     * {@code Pageable.ofSize(1)} to fetch just one candidate.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM LicenseKey k WHERE k.offerCode = :offerCode AND k.status = :status")
    List<LicenseKey> findByOfferCodeAndStatusForUpdate(String offerCode,
                                                        LicenseKey.Status status,
                                                        Pageable pageable);

    /** Find the key a reservation allocated, so release can return it to the pool. */
    Optional<LicenseKey> findByReservationId(String reservationId);
}
