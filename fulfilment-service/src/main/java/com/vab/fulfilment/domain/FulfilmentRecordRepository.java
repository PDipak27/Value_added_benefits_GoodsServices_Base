package com.vab.fulfilment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FulfilmentRecordRepository extends JpaRepository<FulfilmentRecord, String> {

    boolean existsByOrderId(String orderId);

    List<FulfilmentRecord> findByOrderId(String orderId);
}
