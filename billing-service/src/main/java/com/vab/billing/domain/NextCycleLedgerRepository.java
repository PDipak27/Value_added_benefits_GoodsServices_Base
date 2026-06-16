package com.vab.billing.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NextCycleLedgerRepository extends JpaRepository<NextCycleLedgerEntry, String> {
}
