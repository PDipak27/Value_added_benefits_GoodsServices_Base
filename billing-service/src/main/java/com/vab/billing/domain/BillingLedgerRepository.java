package com.vab.billing.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingLedgerRepository extends JpaRepository<BillingLedgerEntry, String> {
}
