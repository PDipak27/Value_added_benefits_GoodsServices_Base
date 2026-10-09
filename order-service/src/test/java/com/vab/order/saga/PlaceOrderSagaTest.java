package com.vab.order.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vab.events.billing.AccountLimitExceeded;
import com.vab.events.billing.AppendToLedgerCommand;
import com.vab.events.billing.AuthorizeBillingCommand;
import com.vab.events.billing.BillingAuthorized;
import com.vab.events.billing.BillingCaptureFailed;
import com.vab.events.billing.BillingCaptured;
import com.vab.events.billing.BillingDeclined;
import com.vab.events.billing.CaptureBillingCommand;
import com.vab.events.billing.CheckAccountLimitCommand;
import com.vab.events.billing.LedgerAppended;
import com.vab.events.billing.RefundBillingCommand;
import com.vab.events.billing.ReverseLedgerCommand;
import com.vab.events.fulfilment.FulfilOrderCommand;
import com.vab.events.fulfilment.OrderFulfilled;
import com.vab.events.fulfilment.OrderFulfilmentFailed;
import com.vab.events.fulfilment.OrderProvisioningFailed;
import com.vab.events.inventory.AllocateInventoryCommand;
import com.vab.events.inventory.CommitInventoryCommand;
import com.vab.events.inventory.InventoryAllocated;
import com.vab.events.inventory.InventoryAllocationFailed;
import com.vab.events.inventory.InventoryCommitFailed;
import com.vab.events.inventory.InventoryReservationFailed;
import com.vab.events.inventory.InventoryReserved;
import com.vab.events.inventory.ReleaseInventoryCommand;
import com.vab.events.inventory.ReserveInventoryCommand;
import com.vab.order.command.service.OrderCommandService;

/**
 * Unit tests for {@link PlaceOrderSaga}'s orchestration logic (DD-26 / DD-27),
 * exercised without Eventuate: each step method is invoked directly with a
 * hand-built {@link PlaceOrderSagaData} and a mocked {@link OrderCommandService}.
 *
 * <p>These lock the parts that carry real branching decisions — not the DSL
 * wiring (that's an integration concern):
 * <ul>
 *   <li>mode routing (PAY_NOW vs BILL_TO_MOBILE) and the forward-recovery gates,</li>
 *   <li>the saga-data → participant-command field mapping,</li>
 *   <li>reply handlers persisting ids onto the saga data,</li>
 *   <li>the FAILED "{reason}: {detail}" reason composition per failed step,</li>
 *   <li>the two cancel checkpoints (pre-pivot rollback vs pre-fulfil forward-recover),</li>
 *   <li>and finalize's three-way COMPLETED / CANCELLED_REFUNDED / FULFILMENT_FAILED routing.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PlaceOrderSagaTest {

    @Mock OrderCommandService orderCommandService;

    private PlaceOrderSaga saga;

    private PlaceOrderSaga sagaUnderTest() {
        return new PlaceOrderSaga(orderCommandService);
    }

    private static PlaceOrderSagaData payNow() {
        return new PlaceOrderSagaData("ord-1", "sub-1", "OFF-1", "SOFTWARE_LICENSE",
                500, "INR", "PAY_NOW", 12);
    }

    private static PlaceOrderSagaData billToMobile() {
        return new PlaceOrderSagaData("ord-2", "sub-2", "OFF-2", "DIGITAL_SUBSCRIPTION",
                750, "INR", "BILL_TO_MOBILE", 6);
    }

    @Nested
    class ModeRoutingAndRecoveryGates {

        @Test
        void payNow_predicates_select_the_pay_now_branch_only() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            assertThat(saga.isPayNow(d)).isTrue();
            assertThat(saga.isBillToMobile(d)).isFalse();
        }

        @Test
        void billToMobile_predicates_select_the_btm_branch_only() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = billToMobile();
            assertThat(saga.isBillToMobile(d)).isTrue();
            assertThat(saga.isPayNow(d)).isFalse();
        }

        @Test
        void refund_gate_fires_only_for_pay_now_while_forward_recovering() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();

            assertThat(saga.shouldRefund(d)).isFalse();      // not recovering yet
            d.setForwardRecover(true);
            assertThat(saga.shouldRefund(d)).isTrue();       // PAY_NOW + recovering
            assertThat(saga.shouldReverseLedger(d)).isFalse(); // wrong mode for ledger reverse
        }

        @Test
        void reverseLedger_gate_fires_only_for_btm_while_forward_recovering() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = billToMobile();

            assertThat(saga.shouldReverseLedger(d)).isFalse();
            d.setForwardRecover(true);
            assertThat(saga.shouldReverseLedger(d)).isTrue();
            assertThat(saga.shouldRefund(d)).isFalse();       // wrong mode for refund
            assertThat(saga.isForwardRecover(d)).isTrue();
        }
    }

    @Nested
    class CommandBuilders {

        @Test
        void reserve_maps_offerCode_and_quantity_one() {
            saga = sagaUnderTest();
            ReserveInventoryCommand cmd = saga.reserveInventory(payNow());
            assertThat(cmd.getOfferCode()).isEqualTo("OFF-1");
            assertThat(cmd.getQuantity()).isEqualTo(1);
        }

        @Test
        void allocate_maps_offerCode_and_quantity_one() {
            saga = sagaUnderTest();
            AllocateInventoryCommand cmd = saga.allocateInventory(billToMobile());
            assertThat(cmd.getOfferCode()).isEqualTo("OFF-2");
            assertThat(cmd.getQuantity()).isEqualTo(1);
        }

        @Test
        void checkAccountLimit_maps_subscriber_amount_currency() {
            saga = sagaUnderTest();
            CheckAccountLimitCommand cmd = saga.checkAccountLimit(billToMobile());
            assertThat(cmd.getSubscriberId()).isEqualTo("sub-2");
            assertThat(cmd.getAmount()).isEqualTo(750);
            assertThat(cmd.getCurrency()).isEqualTo("INR");
        }

        @Test
        void authorize_maps_order_subscriber_amount_currency_mode() {
            saga = sagaUnderTest();
            AuthorizeBillingCommand cmd = saga.authorizeBilling(payNow());
            assertThat(cmd.getOrderId()).isEqualTo("ord-1");
            assertThat(cmd.getSubscriberId()).isEqualTo("sub-1");
            assertThat(cmd.getAmount()).isEqualTo(500);
            assertThat(cmd.getBillingMode()).isEqualTo("PAY_NOW");
        }

        @Test
        void commit_uses_the_reservationId_from_the_reserve_reply() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            d.setReservationId("resv-9");
            CommitInventoryCommand cmd = saga.commitInventory(d);
            assertThat(cmd.getReservationId()).isEqualTo("resv-9");
        }

        @Test
        void capture_uses_the_authId_from_the_authorize_reply() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            d.setAuthId("auth-7");
            CaptureBillingCommand cmd = saga.captureBilling(d);
            assertThat(cmd.getAuthId()).isEqualTo("auth-7");
            assertThat(cmd.getAmount()).isEqualTo(500);
        }

        @Test
        void appendToLedger_maps_order_subscriber_amount_currency() {
            saga = sagaUnderTest();
            AppendToLedgerCommand cmd = saga.appendToLedger(billToMobile());
            assertThat(cmd.getOrderId()).isEqualTo("ord-2");
            assertThat(cmd.getSubscriberId()).isEqualTo("sub-2");
            assertThat(cmd.getAmount()).isEqualTo(750);
        }

        @Test
        void refund_carries_authId_amount_and_cancel_reason() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            d.setAuthId("auth-7");
            d.setCancelReason("USER_CANCEL: pre-fulfil");
            RefundBillingCommand cmd = saga.refundBilling(d);
            assertThat(cmd.getAuthId()).isEqualTo("auth-7");
            assertThat(cmd.getAmount()).isEqualTo(500);
            assertThat(cmd.getReason()).isEqualTo("USER_CANCEL: pre-fulfil");
        }

        @Test
        void reverseLedger_carries_ledgerEntryId_and_cancel_reason() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = billToMobile();
            d.setLedgerEntryId("led-3");
            d.setCancelReason("FULFIL_FAILED");
            ReverseLedgerCommand cmd = saga.reverseLedger(d);
            assertThat(cmd.getLedgerEntryId()).isEqualTo("led-3");
            assertThat(cmd.getReason()).isEqualTo("FULFIL_FAILED");
        }

        @Test
        void release_uses_the_current_reservationId() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            d.setReservationId("resv-9");
            ReleaseInventoryCommand cmd = saga.releaseInventory(d);
            assertThat(cmd.getReservationId()).isEqualTo("resv-9");
        }
    }

    @Nested
    class ReplyHandlers {

        @Test
        void inventoryReserved_captures_reservationId_and_activationKey() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            saga.handleInventoryReserved(d,
                    new InventoryReserved("resv-9", "SOFTWARE_LICENSE", "KEY-123", Instant.now()));
            assertThat(d.getReservationId()).isEqualTo("resv-9");
            assertThat(d.getActivationKey()).isEqualTo("KEY-123");
        }

        @Test
        void inventoryAllocated_captures_reservationId_and_activationKey() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = billToMobile();
            saga.handleInventoryAllocated(d,
                    new InventoryAllocated("resv-5", "DIGITAL_SUBSCRIPTION", "KEY-9"));
            assertThat(d.getReservationId()).isEqualTo("resv-5");
            assertThat(d.getActivationKey()).isEqualTo("KEY-9");
        }

        @Test
        void billingAuthorized_captures_authId() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            saga.handleBillingAuthorized(d, new BillingAuthorized("auth-7"));
            assertThat(d.getAuthId()).isEqualTo("auth-7");
        }

        @Test
        void billingCaptured_captures_captureId() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            saga.handleBillingCaptured(d, new BillingCaptured("cap-2"));
            assertThat(d.getCaptureId()).isEqualTo("cap-2");
        }

        @Test
        void ledgerAppended_captures_ledgerEntryId() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = billToMobile();
            saga.handleLedgerAppended(d, new LedgerAppended("led-3"));
            assertThat(d.getLedgerEntryId()).isEqualTo("led-3");
        }
    }

    /**
     * Every pre-pivot failure reply must fail the order with a "{reason}: {detail}"
     * message and the step label the ops team keys off. These handlers are the only
     * place that mapping lives.
     */
    @Nested
    class FailureHandlers {

        @Test
        void reservationFailed_fails_order_at_RESERVE_INVENTORY() {
            saga = sagaUnderTest();
            saga.handleInventoryReservationFailed(payNow(),
                    new InventoryReservationFailed("OUT_OF_STOCK", "no units"));
            verify(orderCommandService).failOrder("ord-1", "RESERVE_INVENTORY", "OUT_OF_STOCK: no units");
        }

        @Test
        void accountLimitExceeded_fails_order_at_CHECK_ACCOUNT_LIMIT() {
            saga = sagaUnderTest();
            saga.handleAccountLimitExceeded(billToMobile(),
                    new AccountLimitExceeded("LIMIT", "over cap"));
            verify(orderCommandService).failOrder("ord-2", "CHECK_ACCOUNT_LIMIT", "LIMIT: over cap");
        }

        @Test
        void allocationFailed_fails_order_at_ALLOCATE_INVENTORY() {
            saga = sagaUnderTest();
            saga.handleInventoryAllocationFailed(billToMobile(),
                    new InventoryAllocationFailed("OUT_OF_STOCK", "none"));
            verify(orderCommandService).failOrder("ord-2", "ALLOCATE_INVENTORY", "OUT_OF_STOCK: none");
        }

        @Test
        void billingDeclined_fails_order_at_AUTHORIZE_BILLING() {
            saga = sagaUnderTest();
            saga.handleBillingDeclined(payNow(), new BillingDeclined("DECLINED", "insufficient"));
            verify(orderCommandService).failOrder("ord-1", "AUTHORIZE_BILLING", "DECLINED: insufficient");
        }

        @Test
        void commitFailed_fails_order_at_COMMIT_INVENTORY() {
            saga = sagaUnderTest();
            saga.handleInventoryCommitFailed(payNow(),
                    new InventoryCommitFailed("GONE", "reservation expired"));
            verify(orderCommandService).failOrder("ord-1", "COMMIT_INVENTORY", "GONE: reservation expired");
        }

        @Test
        void captureFailed_at_the_pivot_fails_order_at_CAPTURE_BILLING() {
            saga = sagaUnderTest();
            saga.handleBillingCaptureFailed(payNow(),
                    new BillingCaptureFailed("HARD_DECLINE", "bank refused"));
            // Pivot did not commit → order FAILED, nothing captured → no refund path.
            verify(orderCommandService).failOrder("ord-1", "CAPTURE_BILLING", "HARD_DECLINE: bank refused");
        }
    }

    @Nested
    class CancelCheckpoints {

        @Test
        void prePivot_with_pending_cancel_cancels_then_rolls_back() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            when_cancelRequested("ord-1", true);

            assertThatThrownBy(() -> saga.prePivotCancelCheckpoint(d))
                    .isInstanceOf(PlaceOrderSaga.SagaRollback.class);

            verify(orderCommandService).cancel("ord-1", "USER_CANCEL: before pivot");
        }

        @Test
        void prePivot_without_cancel_is_a_no_op() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            when_cancelRequested("ord-1", false);

            saga.prePivotCancelCheckpoint(d);  // no throw

            verify(orderCommandService, never()).cancel(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        void preFulfil_with_pending_cancel_flips_onto_forward_recovery() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            when_cancelRequested("ord-1", true);

            saga.preFulfilCancelCheckpoint(d);

            assertThat(d.isForwardRecover()).isTrue();
            assertThat(d.getCancelReason()).isEqualTo("USER_CANCEL: pre-fulfil");
        }

        @Test
        void preFulfil_without_cancel_leaves_the_happy_path() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            when_cancelRequested("ord-1", false);

            saga.preFulfilCancelCheckpoint(d);

            assertThat(d.isForwardRecover()).isFalse();
            assertThat(d.getCancelReason()).isNull();
        }

        private void when_cancelRequested(String orderId, boolean flag) {
            org.mockito.Mockito.when(orderCommandService.isCancelRequested(orderId)).thenReturn(flag);
        }
    }

    @Nested
    class Fulfil {

        @Test
        void fulfil_runs_only_on_the_happy_forward_path() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            assertThat(saga.shouldFulfil(d)).isTrue();
            d.setForwardRecover(true);
            assertThat(saga.shouldFulfil(d)).isFalse();
        }

        @Test
        void command_carries_type_activation_key_and_term() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            d.setActivationKey("KEY-123");

            FulfilOrderCommand cmd = saga.fulfilOrder(d);

            assertThat(cmd.getOrderId()).isEqualTo("ord-1");
            assertThat(cmd.getSubscriberId()).isEqualTo("sub-1");
            assertThat(cmd.getOfferCode()).isEqualTo("OFF-1");
            assertThat(cmd.getProductType()).isEqualTo("SOFTWARE_LICENSE");
            assertThat(cmd.getActivationKey()).isEqualTo("KEY-123");
            assertThat(cmd.getTermMonths()).isEqualTo(12);
        }

        @Test
        void fulfilled_reply_captures_refs_and_validity() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            Instant from = Instant.parse("2026-01-01T00:00:00Z");
            Instant until = Instant.parse("2027-01-01T00:00:00Z");

            saga.handleOrderFulfilled(d, new OrderFulfilled("ord-1", "DIGITAL_SUBSCRIPTION",
                    "FUL-1", null, null, "OTT-ord-1", from, until));

            assertThat(d.getFulfilmentRef()).isEqualTo("FUL-1");
            assertThat(d.getExternalRef()).isEqualTo("OTT-ord-1");
            assertThat(d.getValidFrom()).isEqualTo(from);
            assertThat(d.getValidUntil()).isEqualTo(until);
        }

        @Test
        void non_transient_failure_flips_onto_forward_recovery() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();

            saga.handleOrderFulfilmentFailed(d, new OrderFulfilmentFailed("DAMAGED", "carrier refused"));

            assertThat(d.isForwardRecover()).isTrue();
            assertThat(d.getCancelReason()).isEqualTo("FULFIL_FAILED: DAMAGED: carrier refused");
            assertThat(d.isProvisioningFailed()).isFalse();
        }

        @Test
        void provisioning_failure_parks_without_forward_recovery() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();

            saga.handleOrderProvisioningFailed(d,
                    new OrderProvisioningFailed("ord-1", "PROVISIONING_UNAVAILABLE", "503"));

            assertThat(d.isProvisioningFailed()).isTrue();
            assertThat(d.getProvisioningFailureReason()).isEqualTo("PROVISIONING_UNAVAILABLE: 503");
            assertThat(d.isForwardRecover()).isFalse();   // DD-27: the charge stands, no refund
        }
    }

    @Nested
    class Finalize {

        @Test
        void confirm_delegates_to_the_service() {
            saga = sagaUnderTest();
            saga.confirmOrder(payNow());
            verify(orderCommandService).confirmOrder("ord-1", "SOFTWARE_LICENSE");
        }

        @Test
        void happy_path_completes_with_the_delivery_artifacts() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            d.setActivationKey("KEY-123");
            d.setExternalRef("OTT-ord-1");

            saga.finalizeOrder(d);

            verify(orderCommandService).completeOrder("ord-1", "SOFTWARE_LICENSE",
                    null, "KEY-123", "OTT-ord-1", null, null);
        }

        @Test
        void forward_recovery_finalizes_as_cancelled_refunded() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            d.setForwardRecover(true);
            d.setCancelReason("USER_CANCEL: pre-fulfil");

            saga.finalizeOrder(d);

            verify(orderCommandService).cancelRefunded("ord-1", "USER_CANCEL: pre-fulfil");
            verify(orderCommandService, never()).completeOrder(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        void provisioning_failure_parks_and_takes_precedence_over_recovery() {
            saga = sagaUnderTest();
            PlaceOrderSagaData d = payNow();
            d.setProvisioningFailed(true);
            d.setProvisioningFailureReason("OTT_TIMEOUT");
            d.setForwardRecover(true);   // park branch must win

            saga.finalizeOrder(d);

            verify(orderCommandService).fulfilmentFailed("ord-1", "OTT_TIMEOUT");
            verifyNoInteractions_onTerminalSuccess();
        }

        private void verifyNoInteractions_onTerminalSuccess() {
            verify(orderCommandService, never()).cancelRefunded(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Test
    void saga_definition_is_built() {
        saga = sagaUnderTest();
        assertThat(saga.getSagaDefinition()).isNotNull();
    }

    @Test
    void constructing_the_saga_does_not_touch_the_service() {
        sagaUnderTest();
        verifyNoInteractions(orderCommandService);
    }
}
