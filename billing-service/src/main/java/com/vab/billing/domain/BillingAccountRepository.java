package com.vab.billing.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BillingAccountRepository extends JpaRepository<BillingAccount, String> {

    /** Pessimistic-lock the account so concurrent charges/releases serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BillingAccount a where a.subscriberId = :id")
    Optional<BillingAccount> findByIdForUpdate(@Param("id") String id);
}
