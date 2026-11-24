package com.vab.messaging;

import java.time.Instant;
import java.util.Map;

/** One row of {@code eventuate.parked_message} (the DLQ). */
public record ParkedMessage(
        long id,
        String subscriberId,
        String channel,
        String messageId,
        Map<String, String> headers,
        String payload,
        FailureClass failureClass,
        String error,
        int attempts,
        Status status,
        Instant parkedAt,
        Instant resolvedAt,
        String redriveMessageId) {

    /**
     * {@code UNROUTABLE}: parked without a {@code DESTINATION} header, so its channel is unknown; it can only be
     * discarded. {@code PROCESSED}: a redelivery of the original succeeded after it was parked, so it was never re-driven.
     */
    public enum Status { PARKED, UNROUTABLE, REDRIVEN, DISCARDED, PROCESSED }
}
