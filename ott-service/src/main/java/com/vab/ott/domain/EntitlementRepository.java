package com.vab.ott.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EntitlementRepository extends JpaRepository<Entitlement, String> {
    Optional<Entitlement> findByOrderId(String orderId);
}
