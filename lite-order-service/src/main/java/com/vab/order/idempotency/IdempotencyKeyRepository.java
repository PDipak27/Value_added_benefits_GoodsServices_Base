package com.vab.order.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
    Optional<IdempotencyKey> findBySubscriberIdAndIdempotencyKey(String subscriberId, String idempotencyKey);
}
