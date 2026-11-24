package com.vab.billing.payment;

import io.eventuate.common.json.mapper.JSonMapper;
import io.eventuate.tram.commands.consumer.CommandReplyToken;

import java.time.Instant;

/** One row of {@code billing.payment_intent} (Design/15). */
public record PaymentIntentRecord(
        String  orderId,
        String  intentId,
        String  subscriberId,
        Long    amount,
        String  currency,
        Status  status,
        String  checkoutUrl,
        Instant expiresAt,
        String  replyTokenJson,
        String  lastError,
        Instant nextAttemptAt) {

    /**
     * CREATED → AUTHORIZED → CAPTURED → (REFUND_OWED →) REFUNDED is the paid path. CREATED can end FAILED
     * (declined), EXPIRED or CANCELLED; an authorisation that won't be captured goes (VOID_OWED →) VOIDED.
     * A CANCELLED row without an intent is a cancel that arrived before the intent was created.
     */
    public enum Status { CREATED, AUTHORIZED, CAPTURED, FAILED, EXPIRED, CANCELLED, VOID_OWED, VOIDED, REFUND_OWED, REFUNDED }

    public boolean awaitingReply() {
        return replyTokenJson != null;
    }

    public CommandReplyToken replyToken() {
        return JSonMapper.fromJson(replyTokenJson, CommandReplyToken.class);
    }
}
