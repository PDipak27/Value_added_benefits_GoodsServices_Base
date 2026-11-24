package com.vab.ott.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    /** Due PENDING events; other delivery instances skip the locked rows. */
    @Query(value = """
            SELECT * FROM ott.webhook_events WHERE status = 'PENDING' AND next_attempt_at <= now()
             ORDER BY next_attempt_at LIMIT :limit FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<WebhookEvent> lockDue(@Param("limit") int limit);
}
