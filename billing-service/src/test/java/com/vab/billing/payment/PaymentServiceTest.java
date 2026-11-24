package com.vab.billing.payment;

import com.vab.billing.domain.BillingLedgerEntry;
import com.vab.billing.domain.BillingLedgerRepository;
import com.vab.billing.payment.PaymentIntentRecord.Status;
import com.vab.billing.payment.PspClient.CallResult;
import com.vab.billing.payment.PspClient.IntentResult;
import com.vab.billing.payment.PspClient.Outcome;
import com.vab.events.billing.AwaitPaymentCommand;
import com.vab.events.billing.BillingCaptureFailed;
import com.vab.events.billing.BillingCaptured;
import com.vab.events.billing.BillingRefunded;
import com.vab.events.billing.CaptureBillingCommand;
import com.vab.events.billing.CreatePaymentIntentCommand;
import com.vab.events.billing.PaymentAuthorized;
import com.vab.events.billing.PaymentIntentCreated;
import com.vab.events.billing.PaymentIntentFailed;
import com.vab.events.billing.PaymentNotCompleted;
import com.vab.events.billing.RefundBillingCommand;
import io.eventuate.common.json.mapper.JSonMapper;
import io.eventuate.tram.commands.common.ReplyMessageHeaders;
import io.eventuate.tram.commands.consumer.CommandReplyProducer;
import io.eventuate.tram.commands.consumer.CommandReplyToken;
import io.eventuate.tram.messaging.common.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Billing's PAY_NOW state machine (Design/15 §5): saga commands, webhooks and the sweeper. */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String ORDER = "ord-1";
    private static final String INTENT = "pi_1";
    private static final Instant EXPIRES = Instant.parse("2026-11-16T10:08:00Z");

    @Mock PaymentIntentStore intents;
    @Mock PspClient psp;
    @Mock CommandReplyProducer replies;
    @Mock BillingLedgerRepository ledger;

    private final PaymentProperties props = new PaymentProperties();
    private final CommandReplyToken token = new CommandReplyToken(Map.of("command_saga_id", "saga-1"), "saga-reply");
    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(intents, psp, replies, ledger, props);
    }

    private PaymentIntentRecord row(Status status, boolean awaitingReply) {
        return new PaymentIntentRecord(ORDER, INTENT, "sub-1", 500L, "INR", status, "http://psp/checkout/pi_1?cs=x",
                EXPIRES, awaitingReply ? JSonMapper.toJson(token) : null, null, null);
    }

    private static <T> T success(Message m, Class<T> type) {
        assertThat(m.getRequiredHeader(ReplyMessageHeaders.REPLY_OUTCOME)).isEqualTo("SUCCESS");
        assertThat(m.getRequiredHeader(ReplyMessageHeaders.REPLY_TYPE)).isEqualTo(type.getName());
        return JSonMapper.fromJson(m.getPayload(), type);
    }

    private static <T> T failure(Message m, Class<T> type) {
        assertThat(m.getRequiredHeader(ReplyMessageHeaders.REPLY_OUTCOME)).isEqualTo("FAILURE");
        assertThat(m.getRequiredHeader(ReplyMessageHeaders.REPLY_TYPE)).isEqualTo(type.getName());
        return JSonMapper.fromJson(m.getPayload(), type);
    }

    /** The one deferred reply sent to the waiting saga. */
    private Message sentReply() {
        ArgumentCaptor<Message> m = ArgumentCaptor.forClass(Message.class);
        verify(replies).sendReplies(any(CommandReplyToken.class), m.capture());
        return m.getValue();
    }

    private BillingLedgerEntry savedLedger() {
        ArgumentCaptor<BillingLedgerEntry> c = ArgumentCaptor.forClass(BillingLedgerEntry.class);
        verify(ledger).save(c.capture());
        return c.getValue();
    }

    private static CallResult ok() { return new CallResult(Outcome.OK, null); }
    private static CallResult down() { return new CallResult(Outcome.UNAVAILABLE, "ConnectException"); }

    @Nested
    class CreateIntent {

        private final CreatePaymentIntentCommand cmd = new CreatePaymentIntentCommand(ORDER, "sub-1", 500, "INR");

        @Test
        void opens_an_intent_and_replies_with_the_checkout_link() {
            when(psp.createIntent(ORDER, 500, "INR"))
                    .thenReturn(new IntentResult(Outcome.OK, INTENT, "http://psp/checkout/pi_1?cs=x", EXPIRES, null));
            when(intents.insertCreated(ORDER, INTENT, "sub-1", 500, "INR", "http://psp/checkout/pi_1?cs=x", EXPIRES))
                    .thenReturn(true);

            PaymentIntentCreated created = success(service.createIntent(cmd), PaymentIntentCreated.class);

            assertThat(created.getIntentId()).isEqualTo(INTENT);
            assertThat(created.getCheckoutUrl()).contains("/checkout/pi_1");
            assertThat(created.getExpiresAt()).isEqualTo(EXPIRES);
        }

        @Test
        void redelivered_command_gets_the_same_intent_without_calling_the_provider() {
            when(intents.lockByOrder(ORDER)).thenReturn(Optional.of(row(Status.CREATED, false)));

            assertThat(success(service.createIntent(cmd), PaymentIntentCreated.class).getIntentId()).isEqualTo(INTENT);
            verifyNoInteractions(psp);
        }

        @Test
        void cancel_tombstone_refuses_without_calling_the_provider() {
            when(intents.lockByOrder(ORDER)).thenReturn(Optional.of(new PaymentIntentRecord(ORDER, null, null, null,
                    null, Status.CANCELLED, null, null, null, "USER_CANCEL", null)));

            assertThat(failure(service.createIntent(cmd), PaymentIntentFailed.class).getReason()).isEqualTo("CANCELLED");
            verifyNoInteractions(psp);
        }

        @Test
        void provider_down_fails_the_step_and_stores_nothing() {
            when(psp.createIntent(ORDER, 500, "INR"))
                    .thenReturn(new IntentResult(Outcome.UNAVAILABLE, null, null, null, "HTTP 503"));

            assertThat(failure(service.createIntent(cmd), PaymentIntentFailed.class).getReason()).isEqualTo("PSP_UNAVAILABLE");
            verify(intents, never()).insertCreated(any(), any(), any(), anyLong(), any(), any(), any());
        }

        @Test
        void cancel_landing_during_the_provider_call_cancels_the_new_intent() {
            when(psp.createIntent(ORDER, 500, "INR"))
                    .thenReturn(new IntentResult(Outcome.OK, INTENT, "http://psp/checkout/pi_1?cs=x", EXPIRES, null));
            when(intents.insertCreated(any(), any(), any(), anyLong(), any(), any(), any())).thenReturn(false);

            assertThat(failure(service.createIntent(cmd), PaymentIntentFailed.class).getReason()).isEqualTo("CANCELLED");
            verify(psp).cancel(INTENT);
        }
    }

    @Nested
    class AwaitPayment {

        private final AwaitPaymentCommand cmd = new AwaitPaymentCommand(ORDER, INTENT);

        @Test
        void unpaid_intent_keeps_the_token_and_replies_later() {
            when(intents.lockByOrder(ORDER)).thenReturn(Optional.of(row(Status.CREATED, false)));

            service.awaitPayment(cmd, token);

            verify(intents).attachReplyToken(ORDER, token);
            verifyNoInteractions(replies);
        }

        @Test
        void already_authorised_replies_at_once() {
            when(intents.lockByOrder(ORDER)).thenReturn(Optional.of(row(Status.AUTHORIZED, false)));

            service.awaitPayment(cmd, token);

            verify(replies).sendReplies(eq(token), any(Message.class));
            assertThat(success(sentReply(), PaymentAuthorized.class).getAuthId()).isEqualTo(INTENT);
        }

        @Test
        void a_closed_intent_replies_why_it_did_not_complete() {
            when(intents.lockByOrder(ORDER)).thenReturn(Optional.of(row(Status.EXPIRED, false)));

            service.awaitPayment(cmd, token);

            assertThat(failure(sentReply(), PaymentNotCompleted.class).getReason()).isEqualTo("EXPIRED");
        }

        @Test
        void declined_intent_replies_declined() {
            when(intents.lockByOrder(ORDER)).thenReturn(Optional.of(row(Status.FAILED, false)));

            service.awaitPayment(cmd, token);

            assertThat(failure(sentReply(), PaymentNotCompleted.class).getReason()).isEqualTo("DECLINED");
        }
    }

    @Nested
    class Cancel {

        @Test
        void before_the_intent_exists_leaves_a_tombstone() {
            service.cancelPayment(ORDER, "USER_CANCEL");

            verify(intents).insertCancelTombstone(ORDER, "USER_CANCEL");
            verifyNoInteractions(psp, replies);
        }

        @Test
        void unpaid_intent_is_cancelled_and_the_waiting_saga_told() {
            when(intents.lockByOrder(ORDER)).thenReturn(Optional.of(row(Status.CREATED, true)));

            service.cancelPayment(ORDER, "USER_CANCEL");

            verify(psp).cancel(INTENT);
            verify(intents).moveTo(ORDER, Status.CANCELLED, "USER_CANCEL");
            assertThat(failure(sentReply(), PaymentNotCompleted.class).getReason()).isEqualTo("CANCELLED");
        }

        @Test
        void a_paid_order_is_left_to_the_saga() {
            when(intents.lockByOrder(ORDER)).thenReturn(Optional.of(row(Status.AUTHORIZED, false)));

            service.cancelPayment(ORDER, "USER_CANCEL");

            verifyNoInteractions(psp, replies);
            verify(intents, never()).moveTo(any(), any(), any());
        }
    }

    @Nested
    class Webhooks {

        @Test
        void duplicate_event_is_ignored() {
            when(intents.recordWebhookEvent("evt_1", "payment.authorized")).thenReturn(false);

            assertThat(service.onWebhook("evt_1", "payment.authorized", INTENT, null)).isFalse();
            verify(intents, never()).lockByIntent(any());
        }

        @Test
        void authorized_answers_the_waiting_saga_and_records_the_ledger() {
            when(intents.recordWebhookEvent("evt_1", "payment.authorized")).thenReturn(true);
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.CREATED, true)));

            service.onWebhook("evt_1", "payment.authorized", INTENT, null);

            assertThat(success(sentReply(), PaymentAuthorized.class).getAuthId()).isEqualTo(INTENT);
            verify(intents).moveTo(ORDER, Status.AUTHORIZED, null);
            assertThat(savedLedger().getType()).isEqualTo(BillingLedgerEntry.Type.AUTHORIZE);
        }

        @Test
        void authorized_before_the_saga_asked_is_kept_for_await_payment() {
            when(intents.recordWebhookEvent("evt_1", "payment.authorized")).thenReturn(true);
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.CREATED, false)));

            service.onWebhook("evt_1", "payment.authorized", INTENT, null);

            verify(intents).moveTo(ORDER, Status.AUTHORIZED, null);
            verifyNoInteractions(replies);
        }

        @Test
        void late_authorisation_after_cancel_is_voided() {
            when(intents.recordWebhookEvent("evt_1", "payment.authorized")).thenReturn(true);
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.CANCELLED, false)));
            when(psp.cancel(INTENT)).thenReturn(ok());

            service.onWebhook("evt_1", "payment.authorized", INTENT, null);

            verify(intents).moveTo(eq(ORDER), eq(Status.VOIDED), anyString());
            verifyNoInteractions(replies);
        }

        @Test
        void late_authorisation_with_the_provider_down_owes_the_void() {
            when(intents.recordWebhookEvent("evt_1", "payment.authorized")).thenReturn(true);
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.EXPIRED, false)));
            when(psp.cancel(INTENT)).thenReturn(down());

            service.onWebhook("evt_1", "payment.authorized", INTENT, null);

            verify(intents).owe(eq(ORDER), eq(Status.VOID_OWED), any(), anyString());
        }

        @Test
        void failed_tells_the_saga_declined() {
            when(intents.recordWebhookEvent("evt_2", "payment.failed")).thenReturn(true);
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.CREATED, true)));

            service.onWebhook("evt_2", "payment.failed", INTENT, "customer_declined");

            PaymentNotCompleted reply = failure(sentReply(), PaymentNotCompleted.class);
            assertThat(reply.getReason()).isEqualTo("DECLINED");
            assertThat(reply.getDetail()).isEqualTo("customer_declined");
            verify(intents).moveTo(ORDER, Status.FAILED, "customer_declined");
        }

        @Test
        void out_of_order_failed_after_authorized_changes_nothing() {
            when(intents.recordWebhookEvent("evt_3", "payment.failed")).thenReturn(true);
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.AUTHORIZED, false)));

            service.onWebhook("evt_3", "payment.failed", INTENT, "late");

            verify(intents, never()).moveTo(any(), any(), any());
            verifyNoInteractions(replies);
        }
    }

    @Nested
    class CaptureVoidRefund {

        private final CaptureBillingCommand capture = new CaptureBillingCommand(INTENT, 500, "INR");

        @Test
        void capture_settles_an_authorisation() {
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.AUTHORIZED, false)));
            when(psp.capture(INTENT)).thenReturn(ok());

            assertThat(success(service.capture(capture), BillingCaptured.class).getCaptureId()).isEqualTo(INTENT);
            verify(intents).moveTo(ORDER, Status.CAPTURED, null);
        }

        @Test
        void capture_declined_fails_the_pivot_and_keeps_the_authorisation_for_the_void() {
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.AUTHORIZED, false)));
            when(psp.capture(INTENT)).thenReturn(new CallResult(Outcome.REJECTED, "HTTP 402 card_declined"));

            assertThat(failure(service.capture(capture), BillingCaptureFailed.class).getReason()).isEqualTo("CAPTURE_DECLINED");
            verify(intents, never()).moveTo(any(), any(), any());
        }

        @Test
        void capture_with_the_provider_down_fails_the_pivot() {
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.AUTHORIZED, false)));
            when(psp.capture(INTENT)).thenReturn(down());

            assertThat(failure(service.capture(capture), BillingCaptureFailed.class).getReason()).isEqualTo("PSP_UNAVAILABLE");
        }

        @Test
        void repeated_capture_is_idempotent() {
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.CAPTURED, false)));

            success(service.capture(capture), BillingCaptured.class);
            verifyNoInteractions(psp);
        }

        @Test
        void void_with_the_provider_down_is_owed_and_still_succeeds() {
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.AUTHORIZED, false)));
            when(psp.cancel(INTENT)).thenReturn(down());

            Message reply = service.voidPayment(INTENT, "rollback");

            assertThat(reply.getRequiredHeader(ReplyMessageHeaders.REPLY_OUTCOME)).isEqualTo("SUCCESS");
            verify(intents).owe(eq(ORDER), eq(Status.VOID_OWED), any(), anyString());
        }

        @Test
        void refund_settles_at_the_provider() {
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.CAPTURED, false)));
            when(psp.refund(INTENT)).thenReturn(ok());

            success(service.refund(new RefundBillingCommand(INTENT, 500, "INR", "USER_CANCEL")), BillingRefunded.class);

            verify(intents).moveTo(ORDER, Status.REFUNDED, null);
            assertThat(savedLedger().getType()).isEqualTo(BillingLedgerEntry.Type.REFUND);
        }

        @Test
        void refund_with_the_provider_down_is_owed_but_the_saga_moves_on() {
            when(intents.lockByIntent(INTENT)).thenReturn(Optional.of(row(Status.CAPTURED, false)));
            when(psp.refund(INTENT)).thenReturn(down());

            success(service.refund(new RefundBillingCommand(INTENT, 500, "INR", "USER_CANCEL")), BillingRefunded.class);

            verify(intents).owe(eq(ORDER), eq(Status.REFUND_OWED), any(), anyString());
        }
    }

    @Nested
    class Sweeper {

        @Test
        void expired_intent_is_closed_and_the_saga_told() {
            when(intents.lockExpired(props.getBatchSize())).thenReturn(List.of(row(Status.CREATED, true)));

            assertThat(service.expireDue()).isEqualTo(1);

            verify(psp).cancel(INTENT);
            verify(intents).moveTo(eq(ORDER), eq(Status.EXPIRED), anyString());
            assertThat(failure(sentReply(), PaymentNotCompleted.class).getReason()).isEqualTo("EXPIRED");
        }

        @Test
        void owed_void_is_done_once_the_provider_answers() {
            when(intents.lockOwedDue(props.getBatchSize())).thenReturn(List.of(row(Status.VOID_OWED, false)));
            when(psp.cancel(INTENT)).thenReturn(ok());

            service.retryOwedDue();

            verify(intents).moveTo(ORDER, Status.VOIDED, null);
        }

        @Test
        void owed_refund_still_failing_is_retried_later() {
            when(intents.lockOwedDue(props.getBatchSize())).thenReturn(List.of(row(Status.REFUND_OWED, false)));
            when(psp.refund(INTENT)).thenReturn(down());

            service.retryOwedDue();

            verify(intents).retryLater(eq(ORDER), any(), anyString());
            verify(intents, never()).moveTo(any(), any(), any());
        }
    }
}
