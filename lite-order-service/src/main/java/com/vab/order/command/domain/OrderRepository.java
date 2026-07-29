package com.vab.order.command.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Write-side repository for the state-stored Order aggregate (post-DD-14).
 * Also backs the read-your-writes fallback (DD-15): a bounded single-key
 * point read by orderId when the Mongo projection has not yet landed.
 */
public interface OrderRepository extends JpaRepository<Order, String> {
}
