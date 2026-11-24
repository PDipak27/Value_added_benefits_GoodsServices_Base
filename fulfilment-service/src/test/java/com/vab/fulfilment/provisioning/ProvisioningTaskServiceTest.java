package com.vab.fulfilment.provisioning;

import com.vab.events.fulfilment.OrderFulfilled;
import com.vab.events.fulfilment.OrderFulfilmentFailed;
import com.vab.events.fulfilment.OrderProvisioningCancelled;
import com.vab.fulfilment.command.OttProvisioningService.Attempt;
import com.vab.fulfilment.domain.FulfilmentRecord;
import com.vab.fulfilment.domain.FulfilmentRecordRepository;
import com.vab.fulfilment.ott.OttClient;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Provisioning task state machine (Design/13 §6): every exit from RETRYING / IN_PROGRESS sends exactly one reply. */
@ExtendWith(MockitoExtension.class)
class ProvisioningTaskServiceTest {

    private static final String ORDER = "ord-1";
    private static final String OWNER = "poller-1";

    @Mock ProvisioningTaskStore tasks;
    @Mock FulfilmentRecordRepository records;
    @Mock CommandReplyProducer replies;
    @Mock ProvisioningMetrics metrics;

    private final ProvisioningProperties props = new ProvisioningProperties();
    private final String tokenJson = JSonMapper.toJson(new CommandReplyToken(Map.of("command_saga_id", "saga-1"), "saga-reply"));
    private ProvisioningTaskService service;

    @BeforeEach
    void setUp() {
        service = new ProvisioningTaskService(tasks, records, replies, props, metrics);
    }

    private ProvisioningTask task(boolean cancelRequested, boolean uncertain, Instant deadline) {
        return new ProvisioningTask(ORDER, "{}", tokenJson, ProvisioningTask.Status.IN_PROGRESS, cancelRequested,
                cancelRequested ? "USER_CANCELLED" : null, uncertain, 2, Instant.now(), deadline, OWNER, null);
    }

    private ProvisioningTask open() {
        return task(false, false, Instant.now().plus(Duration.ofHours(1)));
    }

    private static Attempt attempt(OttClient.Outcome outcome, boolean uncertain) {
        String ref = outcome == OttClient.Outcome.PROVISIONED ? "OTT-1" : null;
        return new Attempt(new OttClient.Result(outcome, ref, "PROVISIONING_" + outcome, "HTTP detail", uncertain),
                ORDER, Instant.now(), Instant.now().plus(Duration.ofDays(30)));
    }

    private Message onlyReply() {
        ArgumentCaptor<Message> reply = ArgumentCaptor.forClass(Message.class);
        verify(replies).sendReplies(any(CommandReplyToken.class), reply.capture());
        Message m = reply.getValue();
        assertThat(m.getRequiredHeader(ReplyMessageHeaders.REPLY_OUTCOME)).isEqualTo("SUCCESS");
        return m;
    }

    private static <T> T payload(Message m, Class<T> type) {
        assertThat(m.getRequiredHeader(ReplyMessageHeaders.REPLY_TYPE)).isEqualTo(type.getName());
        return JSonMapper.fromJson(m.getPayload(), type);
    }

    @Test
    void lost_lease_changes_nothing() {
        when(tasks.lockIfOwned(ORDER, OWNER)).thenReturn(Optional.empty());

        service.finish(open(), attempt(OttClient.Outcome.PROVISIONED, true), OWNER);

        verifyNoInteractions(replies, records);
        verify(tasks, never()).delete(anyString());
    }

    @Test
    void provisioned_saves_record_replies_fulfilled_and_deletes_the_row() {
        when(tasks.lockIfOwned(ORDER, OWNER)).thenReturn(Optional.of(open()));

        service.finish(open(), attempt(OttClient.Outcome.PROVISIONED, true), OWNER);

        ArgumentCaptor<FulfilmentRecord> saved = ArgumentCaptor.forClass(FulfilmentRecord.class);
        verify(records).save(saved.capture());
        OrderFulfilled fulfilled = payload(onlyReply(), OrderFulfilled.class);
        assertThat(fulfilled.getOrderId()).isEqualTo(ORDER);
        assertThat(fulfilled.getExternalRef()).isEqualTo("OTT-1");
        verify(tasks).delete(ORDER);
        verify(metrics).outcome("provisioned_on_retry");
    }

    @Test
    void cancel_that_arrived_during_the_call_wins_over_success_and_owes_a_revoke() {
        when(tasks.lockIfOwned(ORDER, OWNER)).thenReturn(Optional.of(task(true, false, Instant.now().plusSeconds(3600))));

        service.finish(open(), attempt(OttClient.Outcome.PROVISIONED, true), OWNER);

        assertThat(payload(onlyReply(), OrderProvisioningCancelled.class).getReason()).isEqualTo("USER_CANCELLED");
        verifyNoInteractions(records);
        verify(tasks).upsertRevokeOwed(eq(ORDER), any());
        verify(tasks, never()).delete(anyString());
    }

    @Test
    void rejection_replies_failed_and_deletes_when_nothing_was_uncertain() {
        when(tasks.lockIfOwned(ORDER, OWNER)).thenReturn(Optional.of(open()));

        service.finish(open(), attempt(OttClient.Outcome.REJECTED, false), OWNER);

        assertThat(payload(onlyReply(), OrderFulfilmentFailed.class).getReason()).isEqualTo("PROVISIONING_REJECTED");
        verify(tasks).delete(ORDER);
        verify(tasks, never()).upsertRevokeOwed(anyString(), any());
    }

    @Test
    void rejection_after_an_earlier_uncertain_attempt_still_owes_a_revoke() {
        when(tasks.lockIfOwned(ORDER, OWNER)).thenReturn(Optional.of(task(false, true, Instant.now().plusSeconds(3600))));

        service.finish(open(), attempt(OttClient.Outcome.REJECTED, false), OWNER);

        payload(onlyReply(), OrderFulfilmentFailed.class);
        verify(tasks).upsertRevokeOwed(eq(ORDER), any());
    }

    @Test
    void unavailable_before_the_deadline_goes_back_to_retrying_with_backoff_and_no_reply() {
        when(tasks.lockIfOwned(ORDER, OWNER)).thenReturn(Optional.of(open()));
        Instant before = Instant.now();

        service.finish(open(), attempt(OttClient.Outcome.UNAVAILABLE, true), OWNER);

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(tasks).backToRetrying(eq(ORDER), next.capture(), anyString(), anyString(), eq(true));
        assertThat(next.getValue()).isCloseTo(before.plus(props.delayAfter(2)), within(5, ChronoUnit.SECONDS));
        verifyNoInteractions(replies);
    }

    @Test
    void unavailable_past_the_deadline_times_out_and_owes_a_revoke_if_uncertain() {
        ProvisioningTask expired = task(false, false, Instant.now().minusSeconds(1));
        when(tasks.lockIfOwned(ORDER, OWNER)).thenReturn(Optional.of(expired));

        service.finish(expired, attempt(OttClient.Outcome.UNAVAILABLE, true), OWNER);

        assertThat(payload(onlyReply(), OrderFulfilmentFailed.class).getReason()).isEqualTo("PROVISIONING_TIMEOUT");
        verify(tasks).upsertRevokeOwed(eq(ORDER), any());
        verify(tasks, never()).backToRetrying(anyString(), any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void decisions_pass_prefers_cancel_over_deadline() {
        when(tasks.lockDecisionsDue(props.getBatchSize()))
                .thenReturn(List.of(task(true, false, Instant.now().minusSeconds(1))));

        assertThat(service.resolveDecisions()).isEqualTo(1);

        payload(onlyReply(), OrderProvisioningCancelled.class);
        verify(tasks).delete(ORDER);
    }

    @Test
    void decisions_pass_times_out_rows_past_their_deadline() {
        when(tasks.lockDecisionsDue(props.getBatchSize()))
                .thenReturn(List.of(task(false, true, Instant.now().minusSeconds(1))));

        service.resolveDecisions();

        assertThat(payload(onlyReply(), OrderFulfilmentFailed.class).getReason()).isEqualTo("PROVISIONING_TIMEOUT");
        verify(tasks).upsertRevokeOwed(eq(ORDER), any());
    }

    @Test
    void backoff_follows_the_schedule_and_repeats_the_last_step() {
        assertThat(props.delayAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.delayAfter(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(props.delayAfter(99)).isEqualTo(Duration.ofMinutes(10));
        assertThat(props.delayAfter(0)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void accepted_on_retry_waits_for_the_activation_webhook_without_replying() {
        when(tasks.lockIfOwned(ORDER, OWNER)).thenReturn(Optional.of(open()));

        service.finish(open(), attempt(OttClient.Outcome.ACCEPTED, true), OWNER);

        verify(tasks).toAwaiting(eq(ORDER), any());
        verifyNoInteractions(replies, records);
    }

    @Nested
    class OttWebhooks {

        private final Instant from = Instant.parse("2026-11-20T10:00:00Z");
        private final Instant until = Instant.parse("2027-05-20T10:00:00Z");

        private ProvisioningTask awaiting(boolean cancelRequested) {
            return new ProvisioningTask(ORDER, "{}", tokenJson, ProvisioningTask.Status.AWAITING_ACTIVATION, cancelRequested,
                    cancelRequested ? "USER_CANCELLED" : null, true, 1, null, Instant.now().plusSeconds(3600), null, "OTT-9");
        }

        @Test
        void activation_records_the_entitlement_and_answers_the_saga() {
            when(tasks.lock(ORDER)).thenReturn(Optional.of(awaiting(false)));

            service.onActivated(ORDER, "OTT-9", from, until);

            OrderFulfilled fulfilled = payload(onlyReply(), OrderFulfilled.class);
            assertThat(fulfilled.getExternalRef()).isEqualTo("OTT-9");
            assertThat(fulfilled.getValidUntil()).isEqualTo(until);
            ArgumentCaptor<FulfilmentRecord> saved = ArgumentCaptor.forClass(FulfilmentRecord.class);
            verify(records).save(saved.capture());
            assertThat(saved.getValue().getExternalRef()).isEqualTo("OTT-9");
            verify(tasks).delete(ORDER);
        }

        @Test
        void activation_after_a_cancel_answers_cancelled_and_owes_a_revoke() {
            when(tasks.lock(ORDER)).thenReturn(Optional.of(awaiting(true)));

            service.onActivated(ORDER, "OTT-9", from, until);

            payload(onlyReply(), OrderProvisioningCancelled.class);
            verify(tasks).upsertRevokeOwed(eq(ORDER), any());
            verifyNoInteractions(records);
        }

        @Test
        void activation_nobody_waits_for_any_more_is_revoked() {
            when(tasks.lock(ORDER)).thenReturn(Optional.empty());

            service.onActivated(ORDER, "OTT-9", from, until);

            verify(tasks).upsertRevokeOwed(eq(ORDER), any());
            verifyNoInteractions(replies);
        }

        @Test
        void activation_for_an_already_fulfilled_order_changes_nothing() {
            when(tasks.lock(ORDER)).thenReturn(Optional.empty());
            when(records.existsByOrderId(ORDER)).thenReturn(true);

            service.onActivated(ORDER, "OTT-9", from, until);

            verify(tasks, never()).upsertRevokeOwed(anyString(), any());
            verifyNoInteractions(replies);
        }

        @Test
        void rejection_answers_failed_and_closes_the_task() {
            when(tasks.lock(ORDER)).thenReturn(Optional.of(awaiting(false)));

            service.onRejected(ORDER, "Plan not available");

            OrderFulfilmentFailed failed = payload(onlyReply(), OrderFulfilmentFailed.class);
            assertThat(failed.getReason()).isEqualTo("PROVISIONING_REJECTED");
            assertThat(failed.getDetail()).contains("Plan not available");
            verify(tasks).delete(ORDER);
            verify(tasks, never()).upsertRevokeOwed(anyString(), any());
        }

        @Test
        void rejection_for_a_closed_task_is_ignored() {
            when(tasks.lock(ORDER)).thenReturn(Optional.empty());

            service.onRejected(ORDER, "late");

            verifyNoInteractions(replies);
        }
    }
}
