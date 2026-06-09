package com.vab.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryRecordRepository extends JpaRepository<DeliveryRecord, String> {
    List<DeliveryRecord> findByOrderId(String orderId);
}
