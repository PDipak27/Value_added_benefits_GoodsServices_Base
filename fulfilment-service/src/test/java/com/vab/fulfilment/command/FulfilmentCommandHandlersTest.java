package com.vab.fulfilment.command;

import com.vab.events.common.ProductType;
import com.vab.events.fulfilment.*;
import com.vab.fulfilment.domain.FulfilmentRecord;
import com.vab.fulfilment.domain.FulfilmentRecordRepository;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.messaging.common.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Behaviour of the fulfilment saga participant. The single {@code FulfilOrderCommand}
 * dispatches by product type; each path writes a {@link FulfilmentRecord} with
 * exactly one populated delivery artifact and replies {@code OrderFulfilled}.
 */
@ExtendWith(MockitoExtension.class)
class FulfilmentCommandHandlersTest {

    @Mock FulfilmentRecordRepository records;
    @Mock OttProvisioningService ottProvisioning;

    private FulfilmentCommandHandlers handlers;

    @BeforeEach
    void setUp() {
        handlers = new FulfilmentCommandHandlers(records, ottProvisioning);
    }

    @SuppressWarnings("unchecked")
    private static <C> CommandMessage<C> cmd(C command) {
        CommandMessage<C> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(command);
        return cm;
    }

    private FulfilmentRecord savedRecord() {
        ArgumentCaptor<FulfilmentRecord> c = ArgumentCaptor.forClass(FulfilmentRecord.class);
        verify(records).save(c.capture());
        return c.getValue();
    }

    @Nested
    class Fulfil {

        @Test
        void physical_good_produces_a_tracking_ref() {
            Message reply = handlers.fulfil(cmd(new FulfilOrderCommand(
                    "ord-1", "sub-1", "OFF-p", "PHYSICAL_GOOD", null)));

            OrderFulfilled body = Replies.assertSuccess(reply, OrderFulfilled.class);
            assertThat(body.getTrackingRef()).startsWith("TRK");
            assertThat(body.getActivationKey()).isNull();
            assertThat(savedRecord().getProductType()).isEqualTo(ProductType.PHYSICAL_GOOD);
        }

        @Test
        void digital_subscription_delegates_to_ott_and_echoes_external_ref() {
            // DD-27: digital provisioning is delegated; the record is written by
            // OttProvisioningService, so the handler only relays the result.
            when(ottProvisioning.provision("ord-1", "sub-1", "OFF-d"))
                    .thenReturn(new OttProvisioningService.ProvisionResult(
                            true, "ent_x", "OTT-ABC123", null, null));

            Message reply = handlers.fulfil(cmd(new FulfilOrderCommand(
                    "ord-1", "sub-1", "OFF-d", "DIGITAL_SUBSCRIPTION", null)));

            OrderFulfilled body = Replies.assertSuccess(reply, OrderFulfilled.class);
            assertThat(body.getExternalRef()).isEqualTo("OTT-ABC123");
            verify(records, never()).save(any());
        }

        @Test
        void digital_provisioning_failure_parks_with_provisioning_failed() {
            // DD-27: an OTT failure replies OrderProvisioningFailed (success-outcome,
            // park — never a rollback) and is distinct from OrderFulfilmentFailed.
            when(ottProvisioning.provision("ord-1", "sub-1", "OFF-d"))
                    .thenReturn(new OttProvisioningService.ProvisionResult(
                            false, null, null, "PROVISIONING_UNAVAILABLE", "503"));

            Message reply = handlers.fulfil(cmd(new FulfilOrderCommand(
                    "ord-1", "sub-1", "OFF-d", "DIGITAL_SUBSCRIPTION", null)));

            assertThat(Replies.assertSuccess(reply, OrderProvisioningFailed.class).getReason())
                    .isEqualTo("PROVISIONING_UNAVAILABLE");
            verify(records, never()).save(any());
        }

        @Test
        void software_license_echoes_the_pre_allocated_key() {
            Message reply = handlers.fulfil(cmd(new FulfilOrderCommand(
                    "ord-1", "sub-1", "OFF-l", "SOFTWARE_LICENSE", "KEY-1")));

            OrderFulfilled body = Replies.assertSuccess(reply, OrderFulfilled.class);
            assertThat(body.getActivationKey()).isEqualTo("KEY-1");
            assertThat(savedRecord().getActivationKey()).isEqualTo("KEY-1");
        }

        @Test
        void license_without_key_fails_with_no_activation_key_as_success_outcome() {
            // DD-26: fulfil is post-pivot — a non-transient failure is a SUCCESS-outcome
            // branch reply (drives forward-recovery), never a rollback.
            Message reply = handlers.fulfil(cmd(new FulfilOrderCommand(
                    "ord-1", "sub-1", "OFF-l", "SOFTWARE_LICENSE", "  ")));

            assertThat(Replies.assertSuccess(reply, OrderFulfilmentFailed.class).getReason())
                    .isEqualTo("NO_ACTIVATION_KEY");
            verify(records, never()).save(any());
        }

        @Test
        void unparseable_product_type_fails_with_unknown_product_type_as_success_outcome() {
            Message reply = handlers.fulfil(cmd(new FulfilOrderCommand(
                    "ord-1", "sub-1", "OFF-?", "NONSENSE", null)));

            assertThat(Replies.assertSuccess(reply, OrderFulfilmentFailed.class).getReason())
                    .isEqualTo("UNKNOWN_PRODUCT_TYPE");
        }

        @Test
        void offer_code_containing_FAIL_triggers_delivery_failed_as_success_outcome() {
            // DD-26 demo trigger: a non-transient delivery failure (route closed, damaged).
            Message reply = handlers.fulfil(cmd(new FulfilOrderCommand(
                    "ord-1", "sub-1", "OFF-FAIL", "PHYSICAL_GOOD", null)));

            assertThat(Replies.assertSuccess(reply, OrderFulfilmentFailed.class).getReason())
                    .isEqualTo("DELIVERY_FAILED");
            verify(records, never()).save(any());
        }
    }

    @Nested
    class Cancel {

        @Test
        void cancels_an_existing_fulfilled_record() {
            FulfilmentRecord rec = new FulfilmentRecord("shp_1", "ord-1",
                    ProductType.PHYSICAL_GOOD, "TRK1", null, null);
            when(records.findById("shp_1")).thenReturn(Optional.of(rec));

            Message reply = handlers.cancel(cmd(new CancelFulfilmentCommand(
                    "ord-1", "PHYSICAL_GOOD", "shp_1")));

            Replies.assertSuccessOutcome(reply);
            assertThat(rec.getStatus()).isEqualTo(FulfilmentRecord.Status.CANCELLED);
            verify(records).save(rec);
        }

        @Test
        void already_cancelled_record_is_a_no_op() {
            FulfilmentRecord rec = new FulfilmentRecord("shp_1", "ord-1",
                    ProductType.PHYSICAL_GOOD, "TRK1", null, null);
            rec.cancel();
            when(records.findById("shp_1")).thenReturn(Optional.of(rec));

            Message reply = handlers.cancel(cmd(new CancelFulfilmentCommand(
                    "ord-1", "PHYSICAL_GOOD", "shp_1")));

            Replies.assertSuccessOutcome(reply);
            verify(records, never()).save(any());
        }

        @Test
        void unknown_ref_is_a_no_op_success() {
            when(records.findById("missing")).thenReturn(Optional.empty());

            Message reply = handlers.cancel(cmd(new CancelFulfilmentCommand(
                    "ord-1", "PHYSICAL_GOOD", "missing")));

            Replies.assertSuccessOutcome(reply);
            verify(records, never()).save(any());
        }
    }
}
