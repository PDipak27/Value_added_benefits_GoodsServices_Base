package com.vab.inventory.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface LicensePoolRepository extends JpaRepository<LicensePool, String> {

    /** Pessimistic write lock prevents race between concurrent reservations. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM LicensePool p WHERE p.offerCode = :offerCode")
    Optional<LicensePool> findByOfferCodeForUpdate(String offerCode);
}
