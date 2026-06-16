package com.vab.inventory.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, String> {

    /** Pessimistic write lock prevents a race between concurrent reservations. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InventoryItem i WHERE i.offerCode = :offerCode")
    Optional<InventoryItem> findByOfferCodeForUpdate(String offerCode);
}
