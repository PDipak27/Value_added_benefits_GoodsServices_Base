package com.vab.order.command.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Write-side repository for the state-stored Order aggregate (DD-14).
 * Also backs the read-your-writes fallback (DD-15): a bounded single-key
 * point read by orderId when the Mongo projection has not yet landed.
 */
public interface OrderRepository extends JpaRepository<Order, String> {

    /**
     * {@code SELECT … FOR UPDATE} (Design/13 §5.3): user cancel takes the row lock, so a concurrent saga step
     * waits and then fails its {@code @Version} check; the message decorator retries it and it sees the flag.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") String id);
}
