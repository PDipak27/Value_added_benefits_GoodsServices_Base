package com.vab.fulfilment.command;

import com.vab.events.common.ProductType;
import com.vab.events.fulfilment.*;
import com.vab.fulfilment.command.OttProvisioningService.Attempt;
import com.vab.fulfilment.domain.FulfilmentRecord;
import com.vab.fulfilment.domain.FulfilmentRecordRepository;
import com.vab.fulfilment.ott.OttClient;
import com.vab.fulfilment.provisioning.ProvisioningMetrics;
import com.vab.fulfilment.provisioning.ProvisioningProperties;
import com.vab.fulfilment.provisioning.ProvisioningTask;
import com.vab.fulfilment.provisioning.ProvisioningTaskService;
import com.vab.fulfilment.provisioning.ProvisioningTaskStore;
import io.eventuate.tram.commands.consumer.CommandMessage;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Fulfilment saga participant. Replies go through {@link CommandReplyProducer} with the saga's token;
 * a DIGITAL_SUBSCRIPTION whose OTT call is unavailable gets no reply yet (deferred to a provisioning task).
 */
@ExtendWith(MockitoExtension.class)
class FulfilmentCommandHandlersTest {

    @Mock FulfilmentRecordRepository records;
    @Mock OttProvisioningService ottProvisioning;
    @Mock ProvisioningTaskStore tasks;
    @Mock ProvisioningTaskService taskService;
    @Mock ProvisioningMetrics metrics;
    @Mock CommandReplyProducer replies;

    private final ProvisioningProperties props = new ProvisioningProperties();
    private final CommandReplyToken token = new CommandReplyToken(Map.of(), "saga-reply");
    private FulfilmentCommandHandlers handlers;

    @BeforeEach
    void setUp() {
        handlers = new FulfilmentCommandHandlers(records, ottProvisioning, tasks, taskService, props, metrics, replies);
    }

    @SuppressWarnings("unchecked")
    private static <C> CommandMessage<C> cmd(C command) {
        CommandMessage<C> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(command);
        return cm;
    }

    private Message fulfil(FulfilOrderCommand command) {
        handlers.fulfil(cmd(command), token);
        ArgumentCaptor<Message> reply = ArgumentCaptor.forClass(Message.class);
        verify(replies).sendReplies(eq(token), reply.capture());
        return reply.getValue();
    }

    private FulfilmentRecord savedRecord() {
        ArgumentCaptor<FulfilmentRecord> c = ArgumentCaptor.forClass(FulfilmentRecord.class);
        verify(records).save(c.capture());
        return c.getValue();
    }

    private static FulfilOrderCommand digital() {
        return new FulfilOrderCommand("ord-1", "sub-1", "OFF-d", "DIGITAL_SUBSCRIPTION", null, 1);
    }

    private static Attempt attempt(OttClient.Outcome outcome, String ref, String reason, boolean uncertain) {
        return new Attempt(new OttClient.Result(outcome, ref, reason, "detail", uncertain),
                "ord-1", Instant.now(), Instant.now().plusSeconds(60));
    }

    @Nested
    class Fulfil {

        @Test
        void physical_good_produces_a_tracking_ref() {
            OrderFulfilled body = Replies.assertSuccess(fulfil(new FulfilOrderCommand(
                    "ord-1", "sub-1", "OFF-p", "PHYSICAL_GOOD", null)), OrderFulfilled.class);

            assertThat(body.getTrackingRef()).startsWith("TRK");
            assertThat(body.getActivationKey()).isNull();
            assertThat(savedRecord().getProductType()).isEqualTo(ProductType.PHYSICAL_GOOD);
        }

        @Test
        void digital_accepted_holds_the_reply_for_the_activation_webhook() {
            when(ottProvisioning.attempt(any())).thenReturn(attempt(OttClient.Outcome.ACCEPTED, "OTT-1", null, true));

            handlers.fulfil(cmd(digital()), token);

            verifyNoInteractions(replies);
            verify(tasks).insertAwaiting(any(), eq(token), eq("OTT-1"), any());
            verify(records, never()).save(any());
        }

        @Test
        void digital_already_active_records_and_replies_fulfilled() {
            when(ottProvisioning.attempt(any())).thenReturn(attempt(OttClient.Outcome.PROVISIONED, "OTT-1", null, true));

            OrderFulfilled body = Replies.assertSuccess(fulfil(digital()), OrderFulfilled.class);

            assertThat(body.getExternalRef()).isEqualTo("OTT-1");
            assertThat(savedRecord().getExternalRef()).isEqualTo("OTT-1");
            verify(tasks, never()).insertRetrying(any(), any(), any(), any(), anyBoolean(), any(), any());
        }

        @Test
        void digital_rejected_replies_fulfilment_failed_at_once() {
            when(ottProvisioning.attempt(any()))
                    .thenReturn(attempt(OttClient.Outcome.REJECTED, null, "PROVISIONING_REJECTED", false));

            assertThat(Replies.assertSuccess(fulfil(digital()), OrderFulfilmentFailed.class).getReason())
                    .isEqualTo("PROVISIONING_REJECTED");
            verify(records, never()).save(any());
            verify(tasks, never()).insertRetrying(any(), any(), any(), any(), anyBoolean(), any(), any());
        }

        @Test
        void digital_unavailable_defers_the_reply_to_a_task() {
            when(ottProvisioning.attempt(any()))
                    .thenReturn(attempt(OttClient.Outcome.UNAVAILABLE, null, "PROVISIONING_UNAVAILABLE", true));
            when(tasks.insertRetrying(any(), eq(token), eq("PROVISIONING_UNAVAILABLE"), any(), eq(true), any(), any()))
                    .thenReturn(true);

            handlers.fulfil(cmd(digital()), token);

            verifyNoInteractions(replies);
            verify(records, never()).save(any());
        }

        @Test
        void digital_unavailable_with_an_existing_task_fails_the_message() {
            when(ottProvisioning.attempt(any()))
                    .thenReturn(attempt(OttClient.Outcome.UNAVAILABLE, null, "PROVISIONING_UNAVAILABLE", false));
            when(tasks.insertRetrying(any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> handlers.fulfil(cmd(digital()), token)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(replies);
        }

        @Test
        void cancel_tombstone_replies_cancelled_without_calling_ott() {
            when(tasks.consumeTombstone("ord-1")).thenReturn(true);

            assertThat(Replies.assertSuccess(fulfil(digital()), OrderProvisioningCancelled.class).getOrderId())
                    .isEqualTo("ord-1");
            verifyNoInteractions(ottProvisioning);
        }

        @Test
        void software_license_echoes_the_pre_allocated_key() {
            OrderFulfilled body = Replies.assertSuccess(fulfil(new FulfilOrderCommand(
                    "ord-1", "sub-1", "OFF-l", "SOFTWARE_LICENSE", "KEY-1")), OrderFulfilled.class);

            assertThat(body.getActivationKey()).isEqualTo("KEY-1");
            assertThat(savedRecord().getActivationKey()).isEqualTo("KEY-1");
        }

        @Test
        void benefit_with_a_term_sets_a_validity_window() {
            OrderFulfilled body = Replies.assertSuccess(fulfil(new FulfilOrderCommand(
                    "ord-1", "sub-1", "OFF-l", "SOFTWARE_LICENSE", "KEY-1", 3)), OrderFulfilled.class);

            assertThat(body.getValidFrom()).isNotNull();
            assertThat(body.getValidUntil()).isAfter(body.getValidFrom());
        }

        @Test
        void benefit_without_a_term_is_perpetual() {
            OrderFulfilled body = Replies.assertSuccess(fulfil(new FulfilOrderCommand(
                    "ord-1", "sub-1", "OFF-l", "SOFTWARE_LICENSE", "KEY-1", null)), OrderFulfilled.class);

            assertThat(body.getValidUntil()).isNull();
        }

        @Test
        void license_without_key_fails_with_no_activation_key_as_success_outcome() {
            Message reply = fulfil(new FulfilOrderCommand("ord-1", "sub-1", "OFF-l", "SOFTWARE_LICENSE", "  "));

            assertThat(Replies.assertSuccess(reply, OrderFulfilmentFailed.class).getReason()).isEqualTo("NO_ACTIVATION_KEY");
            verify(records, never()).save(any());
        }

        @Test
        void unparseable_product_type_fails_with_unknown_product_type_as_success_outcome() {
            Message reply = fulfil(new FulfilOrderCommand("ord-1", "sub-1", "OFF-?", "NONSENSE", null));

            assertThat(Replies.assertSuccess(reply, OrderFulfilmentFailed.class).getReason()).isEqualTo("UNKNOWN_PRODUCT_TYPE");
        }

        @Test
        void offer_code_containing_FAIL_triggers_delivery_failed_as_success_outcome() {
            Message reply = fulfil(new FulfilOrderCommand("ord-1", "sub-1", "OFF-FAIL", "PHYSICAL_GOOD", null));

            assertThat(Replies.assertSuccess(reply, OrderFulfilmentFailed.class).getReason()).isEqualTo("DELIVERY_FAILED");
            verify(records, never()).save(any());
        }
    }

    @Nested
    class CancelProvisioning {

        private ProvisioningTask task() {
            return new ProvisioningTask("ord-1", "{}", "{}", ProvisioningTask.Status.RETRYING,
                    false, null, false, 1, Instant.now(), Instant.now().plusSeconds(60), null, null);
        }

        @Test
        void open_task_gets_the_cancel_flag() {
            when(tasks.lock("ord-1")).thenReturn(Optional.of(task()));
            when(tasks.requestCancel("ord-1", "USER_CANCEL")).thenReturn(true);

            handlers.cancelProvisioning(cmd(new CancelProvisioningCommand("ord-1", "USER_CANCEL")));

            verify(tasks).requestCancel("ord-1", "USER_CANCEL");
            verify(tasks, never()).insertCancelTombstone(any(), any(), any());
        }

        @Test
        void cancel_before_the_fulfil_command_leaves_a_tombstone() {
            when(tasks.lock("ord-1")).thenReturn(Optional.empty());
            when(records.existsByOrderId("ord-1")).thenReturn(false);

            handlers.cancelProvisioning(cmd(new CancelProvisioningCommand("ord-1", "USER_CANCEL")));

            verify(tasks).insertCancelTombstone(eq("ord-1"), eq("USER_CANCEL"), any());
        }

        @Test
        void already_fulfilled_order_is_a_no_op() {
            when(tasks.lock("ord-1")).thenReturn(Optional.empty());
            when(records.existsByOrderId("ord-1")).thenReturn(true);

            handlers.cancelProvisioning(cmd(new CancelProvisioningCommand("ord-1", "USER_CANCEL")));

            verify(tasks, never()).insertCancelTombstone(any(), any(), any());
            verify(tasks, never()).requestCancel(anyString(), any());
        }
    }

    @Nested
    class ReleaseEntitlement {

        @Test
        void revokes_at_ott_and_cancels_the_record() {
            FulfilmentRecord rec = new FulfilmentRecord("ent_1", "ord-1", ProductType.DIGITAL_SUBSCRIPTION, null, null, "OTT-1");
            when(records.findByOrderId("ord-1")).thenReturn(List.of(rec));
            when(ottProvisioning.revokeByOrder("ord-1")).thenReturn(true);

            Replies.assertSuccessOutcome(handlers.releaseEntitlement(cmd(new ReleaseEntitlementCommand("ord-1", "OTT-1"))));

            assertThat(rec.getStatus()).isEqualTo(FulfilmentRecord.Status.CANCELLED);
            verify(taskService, never()).owesRevoke(any());
        }

        @Test
        void ott_down_owes_the_revoke_but_still_succeeds() {
            when(records.findByOrderId("ord-1")).thenReturn(List.of());
            when(ottProvisioning.revokeByOrder("ord-1")).thenReturn(false);

            Replies.assertSuccessOutcome(handlers.releaseEntitlement(cmd(new ReleaseEntitlementCommand("ord-1", null))));

            verify(taskService).owesRevoke("ord-1");
        }
    }

    @Nested
    class Cancel {

        @Test
        void cancels_an_existing_fulfilled_record() {
            FulfilmentRecord rec = new FulfilmentRecord("shp_1", "ord-1", ProductType.PHYSICAL_GOOD, "TRK1", null, null);
            when(records.findById("shp_1")).thenReturn(Optional.of(rec));

            Replies.assertSuccessOutcome(handlers.cancel(cmd(new CancelFulfilmentCommand("ord-1", "PHYSICAL_GOOD", "shp_1"))));

            assertThat(rec.getStatus()).isEqualTo(FulfilmentRecord.Status.CANCELLED);
            verify(records).save(rec);
        }

        @Test
        void already_cancelled_record_is_a_no_op() {
            FulfilmentRecord rec = new FulfilmentRecord("shp_1", "ord-1", ProductType.PHYSICAL_GOOD, "TRK1", null, null);
            rec.cancel();
            when(records.findById("shp_1")).thenReturn(Optional.of(rec));

            Replies.assertSuccessOutcome(handlers.cancel(cmd(new CancelFulfilmentCommand("ord-1", "PHYSICAL_GOOD", "shp_1"))));

            verify(records, never()).save(any());
        }

        @Test
        void unknown_ref_is_a_no_op_success() {
            when(records.findById("missing")).thenReturn(Optional.empty());

            Replies.assertSuccessOutcome(handlers.cancel(cmd(new CancelFulfilmentCommand("ord-1", "PHYSICAL_GOOD", "missing"))));

            verify(records, never()).save(any());
        }
    }

    @Nested
    class RevokeEntitlement {

        @Test
        void digital_revoke_calls_ott_and_replies_revoked() {
            when(ottProvisioning.revoke("OTT-1")).thenReturn(true);

            Message reply = handlers.revokeEntitlement(cmd(new RevokeEntitlementCommand("ord-1", "DIGITAL_SUBSCRIPTION", "OTT-1")));

            assertThat(Replies.assertSuccess(reply, EntitlementRevoked.class).getOrderId()).isEqualTo("ord-1");
        }

        @Test
        void digital_revoke_ott_failure_replies_revoke_failed() {
            when(ottProvisioning.revoke("OTT-1")).thenReturn(false);

            Message reply = handlers.revokeEntitlement(cmd(new RevokeEntitlementCommand("ord-1", "DIGITAL_SUBSCRIPTION", "OTT-1")));

            assertThat(Replies.assertSuccess(reply, EntitlementRevokeFailed.class).getReason()).isEqualTo("OTT_REVOKE_FAILED");
        }

        @Test
        void license_revoke_is_read_model_only() {
            Message reply = handlers.revokeEntitlement(cmd(new RevokeEntitlementCommand("ord-1", "SOFTWARE_LICENSE", null)));

            assertThat(Replies.assertSuccess(reply, EntitlementRevoked.class).getOrderId()).isEqualTo("ord-1");
            verify(ottProvisioning, never()).revoke(any());
        }
    }
}
