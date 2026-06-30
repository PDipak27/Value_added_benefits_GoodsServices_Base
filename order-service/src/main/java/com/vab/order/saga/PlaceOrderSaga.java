package com.vab.order.saga;

import com.vab.events.billing.AccountLimitExceeded;
import com.vab.events.billing.AccountLimitOk;
import com.vab.events.billing.AppendToLedgerCommand;
import com.vab.events.billing.AuthorizeBillingCommand;
import com.vab.events.billing.BillingAuthorized;
import com.vab.events.billing.BillingCaptureFailed;
import com.vab.events.billing.BillingCaptured;
import com.vab.events.billing.BillingDeclined;
import com.vab.events.billing.BillingRefunded;
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
import com.vab.events.inventory.InventoryCommitted;
import com.vab.events.inventory.InventoryReservationFailed;
import com.vab.events.inventory.InventoryReserved;
import com.vab.events.inventory.ReleaseInventoryCommand;
import com.vab.events.inventory.ReserveInventoryCommand;
import com.vab.order.command.service.OrderCommandService;
import io.eventuate.tram.sagas.orchestration.SagaDefinition;
import io.eventuate.tram.sagas.simpledsl.CommandEndpoint;
import io.eventuate.tram.sagas.simpledsl.CommandEndpointBuilder;
import io.eventuate.tram.sagas.simpledsl.SimpleSaga;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * PlaceOrderSaga — one linear orchestrator, two payment-mode flows (DD-26).
 *
 * <p><b>Pivot = the charge.</b> Capture (PAY_NOW) / append-to-ledger
 * (BILL_TO_MOBILE) is the go/no-go point; the order is confirmed immediately
 * after. Everything up to the pivot is compensatable and rolls back LIFO on
 * failure. Everything after it is forward-only — those steps never reply
 * {@code withFailure} (Eventuate has no auto-retry; a failure reply would instead
 * trigger compensation), so a post-pivot problem is handled by an explicit
 * forward-recovery branch rather than a rollback.
 *
 * <p><b>Capture decline</b> is the pivot failing to commit: it replies
 * {@code withFailure}, the prior holds are released, and the order ends FAILED
 * (no charge → no refund).
 *
 * <p><b>Forward-recovery</b> (sets {@code forwardRecover}) fires when fulfilment
 * fails for a non-transient reason (success-outcome {@code OrderFulfilmentFailed}
 * branch) or a user cancel is seen at the pre-fulfil checkpoint. It refunds
 * (PAY_NOW) / reverses the ledger (BILL_TO_MOBILE), releases inventory, and ends
 * the order CANCELLED_REFUNDED.
 *
 * <p><b>User cancel</b> is cooperative (a flag on the order, set by the API). The
 * saga reads it at two checkpoints: before the pivot it cancels + rolls back
 * (CANCELLED); in the pre-fulfil window it forward-recovers (CANCELLED_REFUNDED).
 * A cancel that arrives after the pre-fulfil checkpoint loses the race and the
 * order completes normally; once COMPLETED the API refuses cancel (409).
 *
 * <pre>
 *  PAY_NOW : reserve → authorize → commit → [cancel?] → CAPTURE(pivot) → confirm
 *            → [cancel?] → fulfil → (refund → release) → finalize
 *  BTM     : checkLimit → allocate → [cancel?] → APPEND_LEDGER(pivot) → confirm
 *            → [cancel?] → fulfil → (reverseLedger → release) → finalize
 * </pre>
 */
@Component
public class PlaceOrderSaga implements SimpleSaga<PlaceOrderSagaData> {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderSaga.class);

    private static final String PAY_NOW        = "PAY_NOW";
    private static final String BILL_TO_MOBILE = "BILL_TO_MOBILE";

    // Channel names must match each participant's @SagaCommandHandlersBuilder channel
    public static final String INVENTORY_CHANNEL  = "inventoryService";
    public static final String BILLING_CHANNEL    = "billingService";
    public static final String FULFILMENT_CHANNEL = "fulfilmentService";

    // ── Inventory endpoints ───────────────────────────────────────────────────

    private final CommandEndpoint<ReserveInventoryCommand> reserveInventoryEndpoint =
            CommandEndpointBuilder.forCommand(ReserveInventoryCommand.class)
                    .withChannel(INVENTORY_CHANNEL)
                    .withReply(InventoryReserved.class)
                    .withReply(InventoryReservationFailed.class)
                    .build();

    private final CommandEndpoint<AllocateInventoryCommand> allocateInventoryEndpoint =
            CommandEndpointBuilder.forCommand(AllocateInventoryCommand.class)
                    .withChannel(INVENTORY_CHANNEL)
                    .withReply(InventoryAllocated.class)
                    .withReply(InventoryAllocationFailed.class)
                    .build();

    private final CommandEndpoint<CommitInventoryCommand> commitInventoryEndpoint =
            CommandEndpointBuilder.forCommand(CommitInventoryCommand.class)
                    .withChannel(INVENTORY_CHANNEL)
                    .withReply(InventoryCommitted.class)
                    .withReply(InventoryCommitFailed.class)
                    .build();

    private final CommandEndpoint<ReleaseInventoryCommand> releaseInventoryEndpoint =
            CommandEndpointBuilder.forCommand(ReleaseInventoryCommand.class)
                    .withChannel(INVENTORY_CHANNEL)
                    .build();

    // ── Billing endpoints ─────────────────────────────────────────────────────

    private final CommandEndpoint<CheckAccountLimitCommand> checkAccountLimitEndpoint =
            CommandEndpointBuilder.forCommand(CheckAccountLimitCommand.class)
                    .withChannel(BILLING_CHANNEL)
                    .withReply(AccountLimitOk.class)
                    .withReply(AccountLimitExceeded.class)
                    .build();

    private final CommandEndpoint<AuthorizeBillingCommand> authorizeBillingEndpoint =
            CommandEndpointBuilder.forCommand(AuthorizeBillingCommand.class)
                    .withChannel(BILLING_CHANNEL)
                    .withReply(BillingAuthorized.class)
                    .withReply(BillingDeclined.class)
                    .build();

    private final CommandEndpoint<AppendToLedgerCommand> appendToLedgerEndpoint =
            CommandEndpointBuilder.forCommand(AppendToLedgerCommand.class)
                    .withChannel(BILLING_CHANNEL)
                    .withReply(LedgerAppended.class)
                    .build();

    private final CommandEndpoint<ReverseLedgerCommand> reverseLedgerEndpoint =
            CommandEndpointBuilder.forCommand(ReverseLedgerCommand.class)
                    .withChannel(BILLING_CHANNEL)
                    .build();

    private final CommandEndpoint<CaptureBillingCommand> captureBillingEndpoint =
            CommandEndpointBuilder.forCommand(CaptureBillingCommand.class)
                    .withChannel(BILLING_CHANNEL)
                    .withReply(BillingCaptured.class)
                    .withReply(BillingCaptureFailed.class)
                    .build();

    private final CommandEndpoint<RefundBillingCommand> refundBillingEndpoint =
            CommandEndpointBuilder.forCommand(RefundBillingCommand.class)
                    .withChannel(BILLING_CHANNEL)
                    .withReply(BillingRefunded.class)
                    .build();

    // ── Fulfilment endpoints ──────────────────────────────────────────────────

    private final CommandEndpoint<FulfilOrderCommand> fulfilOrderEndpoint =
            CommandEndpointBuilder.forCommand(FulfilOrderCommand.class)
                    .withChannel(FULFILMENT_CHANNEL)
                    .withReply(OrderFulfilled.class)
                    .withReply(OrderFulfilmentFailed.class)
                    .withReply(OrderProvisioningFailed.class)
                    .build();

    private final OrderCommandService orderCommandService;

    private final SagaDefinition<PlaceOrderSagaData> sagaDefinition;

    public PlaceOrderSaga(@Lazy OrderCommandService orderCommandService) {
        this.orderCommandService = orderCommandService;

        this.sagaDefinition = step()
                // 1 — PAY_NOW: reserve a temporary hold (comp: release).
                .invokeParticipant(this::isPayNow, reserveInventoryEndpoint, this::reserveInventory)
                .onReply(InventoryReserved.class, this::handleInventoryReserved)
                .onReply(InventoryReservationFailed.class, this::handleInventoryReservationFailed)
                .withCompensation(this::isPayNow, releaseInventoryEndpoint, this::releaseInventory)
            .step()
                // 2 — BILL_TO_MOBILE: verify account status + credit limit.
                .invokeParticipant(this::isBillToMobile, checkAccountLimitEndpoint, this::checkAccountLimit)
                .onReply(AccountLimitOk.class, this::handleAccountLimitOk)
                .onReply(AccountLimitExceeded.class, this::handleAccountLimitExceeded)
            .step()
                // 3 — BILL_TO_MOBILE: firm one-step allocation (comp: release).
                .invokeParticipant(this::isBillToMobile, allocateInventoryEndpoint, this::allocateInventory)
                .onReply(InventoryAllocated.class, this::handleInventoryAllocated)
                .onReply(InventoryAllocationFailed.class, this::handleInventoryAllocationFailed)
                .withCompensation(this::isBillToMobile, releaseInventoryEndpoint, this::releaseInventory)
            .step()
                // 4 — PAY_NOW: authorize payment (pre-capture, no comp).
                .invokeParticipant(this::isPayNow, authorizeBillingEndpoint, this::authorizeBilling)
                .onReply(BillingAuthorized.class, this::handleBillingAuthorized)
                .onReply(BillingDeclined.class, this::handleBillingDeclined)
            .step()
                // 5 — PAY_NOW: promote the hold RESERVED → ALLOCATED (no comp).
                .invokeParticipant(this::isPayNow, commitInventoryEndpoint, this::commitInventory)
                .onReply(InventoryCommitted.class, this::handleInventoryCommitted)
                .onReply(InventoryCommitFailed.class, this::handleInventoryCommitFailed)
            .step()
                // 6 — pre-pivot cancel checkpoint (local, both modes). A pending
                //     cancel here is honoured by rolling back: mark CANCELLED then
                //     throw so Eventuate compensates the holds above. Nothing charged.
                .invokeLocal(this::prePivotCancelCheckpoint)
            .step()
                // 7 — PAY_NOW PIVOT: capture the payment. A hard decline replies
                //     withFailure → the pivot did not commit → roll back to FAILED.
                .invokeParticipant(this::isPayNow, captureBillingEndpoint, this::captureBilling)
                .onReply(BillingCaptured.class, this::handleBillingCaptured)
                .onReply(BillingCaptureFailed.class, this::handleBillingCaptureFailed)
            .step()
                // 8 — BILL_TO_MOBILE PIVOT: park the charge on the next cycle.
                //     Forward-only from here (reversed by forward-recovery, not comp).
                .invokeParticipant(this::isBillToMobile, appendToLedgerEndpoint, this::appendToLedger)
                .onReply(LedgerAppended.class, this::handleLedgerAppended)
            .step()
                // 9 — confirm (local, both); intermediate state, no artifact yet.
                .invokeLocal(this::confirmOrder)
            .step()
                // 10 — pre-fulfil cancel checkpoint (local, both). Post-pivot, so a
                //      pending cancel forward-recovers (no rollback): flip the branch.
                .invokeLocal(this::preFulfilCancelCheckpoint)
            .step()
                // 11 — fulfil (both); forward-only. Skipped if already forward-recovering.
                //      A non-transient failure is a success-outcome branch reply that
                //      flips onto forward-recovery rather than compensating.
                .invokeParticipant(this::shouldFulfil, fulfilOrderEndpoint, this::fulfilOrder)
                .onReply(OrderFulfilled.class, this::handleOrderFulfilled)
                .onReply(OrderFulfilmentFailed.class, this::handleOrderFulfilmentFailed)
                .onReply(OrderProvisioningFailed.class, this::handleOrderProvisioningFailed)
            .step()
                // 12 — forward-recovery: refund the captured charge (PAY_NOW).
                .invokeParticipant(this::shouldRefund, refundBillingEndpoint, this::refundBilling)
                .onReply(BillingRefunded.class, this::handleBillingRefunded)
            .step()
                // 13 — forward-recovery: reverse the next-cycle charge (BILL_TO_MOBILE).
                .invokeParticipant(this::shouldReverseLedger, reverseLedgerEndpoint, this::reverseLedger)
            .step()
                // 14 — forward-recovery: release the inventory hold (both modes).
                .invokeParticipant(this::isForwardRecover, releaseInventoryEndpoint, this::releaseInventory)
            .step()
                // 15 — finalize (local, both): COMPLETED on the happy path,
                //      CANCELLED_REFUNDED when forward-recovery ran.
                .invokeLocal(this::finalizeOrder)
            .build();
    }

    @Override
    public SagaDefinition<PlaceOrderSagaData> getSagaDefinition() {
        return sagaDefinition;
    }

    // ── Predicates ─────────────────────────────────────────────────────────────

    private boolean isPayNow(PlaceOrderSagaData data) {
        return PAY_NOW.equals(data.getBillingMode());
    }

    private boolean isBillToMobile(PlaceOrderSagaData data) {
        return BILL_TO_MOBILE.equals(data.getBillingMode());
    }

    private boolean isForwardRecover(PlaceOrderSagaData data) {
        return data.isForwardRecover();
    }

    /** Fulfil only on the happy forward path — skipped once forward-recovering (DD-26). */
    private boolean shouldFulfil(PlaceOrderSagaData data) {
        return !data.isForwardRecover();
    }

    private boolean shouldRefund(PlaceOrderSagaData data) {
        return isPayNow(data) && data.isForwardRecover();
    }

    private boolean shouldReverseLedger(PlaceOrderSagaData data) {
        return isBillToMobile(data) && data.isForwardRecover();
    }

    // ── Step 1: Reserve (PAY_NOW) ───────────────────────────────────────────────

    private ReserveInventoryCommand reserveInventory(PlaceOrderSagaData data) {
        log.info("Saga reserve: orderId={}, offerCode={}, productType={}",
                data.getOrderId(), data.getOfferCode(), data.getProductType());
        return new ReserveInventoryCommand(data.getOfferCode(), 1);
    }

    private void handleInventoryReserved(PlaceOrderSagaData data, InventoryReserved reply) {
        log.info("Saga reserve OK: orderId={}, reservationId={}, type={}, key={}, until={}",
                data.getOrderId(), reply.getReservationId(), reply.getProductType(),
                reply.getActivationKey(), reply.getReservedUntil());
        data.setReservationId(reply.getReservationId());
        data.setActivationKey(reply.getActivationKey());   // SOFTWARE_LICENSE: key claimed here
    }

    private void handleInventoryReservationFailed(PlaceOrderSagaData data, InventoryReservationFailed reply) {
        log.warn("Saga reserve FAILED: orderId={}, reason={}, detail={}",
                data.getOrderId(), reply.getReason(), reply.getDetail());
        orderCommandService.failOrder(data.getOrderId(), "RESERVE_INVENTORY",
                reply.getReason() + ": " + reply.getDetail());
    }

    private ReleaseInventoryCommand releaseInventory(PlaceOrderSagaData data) {
        log.info("Saga release inventory: orderId={}, reservationId={}",
                data.getOrderId(), data.getReservationId());
        return new ReleaseInventoryCommand(data.getReservationId());
    }

    // ── Step 2: Check account limit (BILL_TO_MOBILE) ────────────────────────────

    private CheckAccountLimitCommand checkAccountLimit(PlaceOrderSagaData data) {
        log.info("Saga checkLimit: orderId={}, subscriberId={}, amount={} {}",
                data.getOrderId(), data.getSubscriberId(), data.getAmount(), data.getCurrency());
        return new CheckAccountLimitCommand(data.getSubscriberId(), data.getAmount(), data.getCurrency());
    }

    private void handleAccountLimitOk(PlaceOrderSagaData data, AccountLimitOk reply) {
        log.info("Saga checkLimit OK: orderId={}, subscriberId={}", data.getOrderId(), reply.getSubscriberId());
    }

    private void handleAccountLimitExceeded(PlaceOrderSagaData data, AccountLimitExceeded reply) {
        log.warn("Saga checkLimit FAILED: orderId={}, reason={}, detail={}",
                data.getOrderId(), reply.getReason(), reply.getDetail());
        orderCommandService.failOrder(data.getOrderId(), "CHECK_ACCOUNT_LIMIT",
                reply.getReason() + ": " + reply.getDetail());
    }

    // ── Step 3: Allocate (BILL_TO_MOBILE) ───────────────────────────────────────

    private AllocateInventoryCommand allocateInventory(PlaceOrderSagaData data) {
        log.info("Saga allocate: orderId={}, offerCode={}, productType={}",
                data.getOrderId(), data.getOfferCode(), data.getProductType());
        return new AllocateInventoryCommand(data.getOfferCode(), 1);
    }

    private void handleInventoryAllocated(PlaceOrderSagaData data, InventoryAllocated reply) {
        log.info("Saga allocate OK: orderId={}, reservationId={}, type={}, key={}",
                data.getOrderId(), reply.getReservationId(), reply.getProductType(), reply.getActivationKey());
        data.setReservationId(reply.getReservationId());
        data.setActivationKey(reply.getActivationKey());
    }

    private void handleInventoryAllocationFailed(PlaceOrderSagaData data, InventoryAllocationFailed reply) {
        log.warn("Saga allocate FAILED: orderId={}, reason={}, detail={}",
                data.getOrderId(), reply.getReason(), reply.getDetail());
        orderCommandService.failOrder(data.getOrderId(), "ALLOCATE_INVENTORY",
                reply.getReason() + ": " + reply.getDetail());
    }

    // ── Step 4: Authorize billing (PAY_NOW) ─────────────────────────────────────

    private AuthorizeBillingCommand authorizeBilling(PlaceOrderSagaData data) {
        log.info("Saga authorize: orderId={}, amount={} {}",
                data.getOrderId(), data.getAmount(), data.getCurrency());
        return new AuthorizeBillingCommand(data.getOrderId(), data.getSubscriberId(),
                data.getAmount(), data.getCurrency(), data.getBillingMode());
    }

    private void handleBillingAuthorized(PlaceOrderSagaData data, BillingAuthorized reply) {
        log.info("Saga authorize OK: orderId={}, authId={}", data.getOrderId(), reply.getAuthId());
        data.setAuthId(reply.getAuthId());
    }

    private void handleBillingDeclined(PlaceOrderSagaData data, BillingDeclined reply) {
        log.warn("Saga authorize DECLINED: orderId={}, reason={}, detail={}",
                data.getOrderId(), reply.getReason(), reply.getDetail());
        orderCommandService.failOrder(data.getOrderId(), "AUTHORIZE_BILLING",
                reply.getReason() + ": " + reply.getDetail());
    }

    // ── Step 5: Commit inventory (PAY_NOW) ──────────────────────────────────────

    private CommitInventoryCommand commitInventory(PlaceOrderSagaData data) {
        log.info("Saga commit: orderId={}, reservationId={}", data.getOrderId(), data.getReservationId());
        return new CommitInventoryCommand(data.getReservationId());
    }

    private void handleInventoryCommitted(PlaceOrderSagaData data, InventoryCommitted reply) {
        log.info("Saga commit OK: orderId={}, reservationId={}", data.getOrderId(), reply.getReservationId());
    }

    private void handleInventoryCommitFailed(PlaceOrderSagaData data, InventoryCommitFailed reply) {
        log.warn("Saga commit FAILED: orderId={}, reason={}, detail={}",
                data.getOrderId(), reply.getReason(), reply.getDetail());
        orderCommandService.failOrder(data.getOrderId(), "COMMIT_INVENTORY",
                reply.getReason() + ": " + reply.getDetail());
    }

    // ── Step 6: Pre-pivot cancel checkpoint (local, both) ───────────────────────

    private void prePivotCancelCheckpoint(PlaceOrderSagaData data) {
        if (orderCommandService.isCancelRequested(data.getOrderId())) {
            log.info("Saga cancel (pre-pivot): orderId={} — rolling back", data.getOrderId());
            orderCommandService.cancel(data.getOrderId(), "USER_CANCEL: before pivot");
            // Throw so Eventuate compensates the holds taken above (no charge yet).
            throw new SagaRollback("cancel requested before pivot");
        }
    }

    // ── Step 7: Capture billing (PAY_NOW pivot) ─────────────────────────────────

    private CaptureBillingCommand captureBilling(PlaceOrderSagaData data) {
        log.info("Saga capture (pivot): orderId={}, authId={}", data.getOrderId(), data.getAuthId());
        return new CaptureBillingCommand(data.getAuthId(), data.getAmount(), data.getCurrency());
    }

    private void handleBillingCaptured(PlaceOrderSagaData data, BillingCaptured reply) {
        log.info("Saga capture OK: orderId={}, captureId={}", data.getOrderId(), reply.getCaptureId());
        data.setCaptureId(reply.getCaptureId());
    }

    private void handleBillingCaptureFailed(PlaceOrderSagaData data, BillingCaptureFailed reply) {
        // Pivot did not commit (withFailure reply). Eventuate now rolls back the
        // prior holds; mark the order FAILED. Nothing was captured → no refund.
        log.warn("Saga capture DECLINED (rollback): orderId={}, reason={}, detail={}",
                data.getOrderId(), reply.getReason(), reply.getDetail());
        orderCommandService.failOrder(data.getOrderId(), "CAPTURE_BILLING",
                reply.getReason() + ": " + reply.getDetail());
    }

    // ── Step 8: Append to ledger (BILL_TO_MOBILE pivot) ─────────────────────────

    private AppendToLedgerCommand appendToLedger(PlaceOrderSagaData data) {
        log.info("Saga appendLedger (pivot): orderId={}, subscriberId={}, amount={} {}",
                data.getOrderId(), data.getSubscriberId(), data.getAmount(), data.getCurrency());
        return new AppendToLedgerCommand(data.getOrderId(), data.getSubscriberId(),
                data.getAmount(), data.getCurrency());
    }

    private void handleLedgerAppended(PlaceOrderSagaData data, LedgerAppended reply) {
        log.info("Saga appendLedger OK: orderId={}, ledgerEntryId={}",
                data.getOrderId(), reply.getLedgerEntryId());
        data.setLedgerEntryId(reply.getLedgerEntryId());
    }

    // ── Step 9: Confirm (local, both) ───────────────────────────────────────────

    private void confirmOrder(PlaceOrderSagaData data) {
        log.info("Saga confirm (local): orderId={}", data.getOrderId());
        orderCommandService.confirmOrder(data.getOrderId(), data.getProductType());
    }

    // ── Step 10: Pre-fulfil cancel checkpoint (local, both) ─────────────────────

    private void preFulfilCancelCheckpoint(PlaceOrderSagaData data) {
        if (orderCommandService.isCancelRequested(data.getOrderId())) {
            // Post-pivot: cannot roll back the charge. Flip onto forward-recovery.
            log.info("Saga cancel (pre-fulfil): orderId={} — forward-recovering", data.getOrderId());
            data.setForwardRecover(true);
            data.setCancelReason("USER_CANCEL: pre-fulfil");
        }
    }

    // ── Step 11: Fulfil (both; skipped while forward-recovering) ────────────────

    private FulfilOrderCommand fulfilOrder(PlaceOrderSagaData data) {
        log.info("Saga fulfil: orderId={}, productType={}, termMonths={}",
                data.getOrderId(), data.getProductType(), data.getTermMonths());
        return new FulfilOrderCommand(data.getOrderId(), data.getSubscriberId(),
                data.getOfferCode(), data.getProductType(), data.getActivationKey(), data.getTermMonths());
    }

    private void handleOrderFulfilled(PlaceOrderSagaData data, OrderFulfilled reply) {
        log.info("Saga fulfil OK: orderId={}, type={}, fulfilmentRef={}, validUntil={}",
                data.getOrderId(), reply.getProductType(), reply.getFulfilmentRef(), reply.getValidUntil());
        data.setFulfilmentRef(reply.getFulfilmentRef());
        data.setTrackingRef(reply.getTrackingRef());
        data.setActivationKey(reply.getActivationKey());
        data.setExternalRef(reply.getExternalRef());
        data.setValidFrom(reply.getValidFrom());
        data.setValidUntil(reply.getValidUntil());
    }

    private void handleOrderFulfilmentFailed(PlaceOrderSagaData data, OrderFulfilmentFailed reply) {
        // Non-transient failure (success-outcome branch reply). No rollback —
        // flip onto forward-recovery: refund/reverse + release → CANCELLED_REFUNDED.
        log.warn("Saga fulfil FAILED (forward-recover): orderId={}, reason={}, detail={}",
                data.getOrderId(), reply.getReason(), reply.getDetail());
        data.setForwardRecover(true);
        data.setCancelReason(cap("FULFIL_FAILED: " + reply.getReason() + ": " + reply.getDetail()));
    }

    private void handleOrderProvisioningFailed(PlaceOrderSagaData data, OrderProvisioningFailed reply) {
        // OTT provisioning failed (DD-27). Success-outcome reply, so NO compensation
        // and — unlike forward-recovery — NO refund: the charge stands. Park the
        // order in FULFILMENT_FAILED at finalize for admin re-drive. Distinct flag so
        // the refund/reverse/release steps stay skipped (they gate on forwardRecover).
        log.warn("Saga provisioning FAILED (park → FULFILMENT_FAILED): orderId={}, reason={}, detail={}",
                data.getOrderId(), reply.getReason(), reply.getDetail());
        data.setProvisioningFailed(true);
        data.setProvisioningFailureReason(cap(reply.getReason() + ": " + reply.getDetail()));
    }

    /**
     * Bound a failure string before it enters PlaceOrderSagaData — Eventuate's
     * saga_instance.saga_data_json column is small (VARCHAR), so a verbose
     * participant detail (e.g. an upstream stack trace) must not overflow the row.
     */
    private static String cap(String s) {
        return (s == null || s.length() <= 256) ? s : s.substring(0, 256) + "...";
    }

    // ── Step 12: Refund (PAY_NOW forward-recovery) ──────────────────────────────

    private RefundBillingCommand refundBilling(PlaceOrderSagaData data) {
        log.info("Saga refund (forward-recovery): orderId={}, authId={}", data.getOrderId(), data.getAuthId());
        return new RefundBillingCommand(data.getAuthId(), data.getAmount(), data.getCurrency(),
                data.getCancelReason());
    }

    private void handleBillingRefunded(PlaceOrderSagaData data, BillingRefunded reply) {
        log.info("Saga refund OK: orderId={}, refundId={}", data.getOrderId(), reply.getRefundId());
    }

    // ── Step 13: Reverse ledger (BILL_TO_MOBILE forward-recovery) ───────────────

    private ReverseLedgerCommand reverseLedger(PlaceOrderSagaData data) {
        log.info("Saga reverseLedger (forward-recovery): orderId={}, ledgerEntryId={}",
                data.getOrderId(), data.getLedgerEntryId());
        return new ReverseLedgerCommand(data.getLedgerEntryId(), data.getCancelReason());
    }

    // ── Step 15: Finalize (local, both) ─────────────────────────────────────────

    private void finalizeOrder(PlaceOrderSagaData data) {
        if (data.isProvisioningFailed()) {
            // DD-27: OTT park — charge stands, no unwind. Branch on reply content,
            // never complete unconditionally.
            log.info("Saga finalize → FULFILMENT_FAILED: orderId={}", data.getOrderId());
            orderCommandService.fulfilmentFailed(data.getOrderId(), data.getProvisioningFailureReason());
            return;
        }
        if (data.isForwardRecover()) {
            log.info("Saga finalize → CANCELLED_REFUNDED: orderId={}", data.getOrderId());
            orderCommandService.cancelRefunded(data.getOrderId(), data.getCancelReason());
            return;
        }
        log.info("Saga finalize → COMPLETED: orderId={}", data.getOrderId());
        orderCommandService.completeOrder(data.getOrderId(), data.getProductType(),
                data.getTrackingRef(), data.getActivationKey(), data.getExternalRef(),
                data.getValidFrom(), data.getValidUntil());
    }

    /** Signals the pre-pivot cancel checkpoint wants Eventuate to roll back the saga. */
    static final class SagaRollback extends RuntimeException {
        private static final long serialVersionUID = -6216360105218809812L;

		SagaRollback(String message) { super(message); }
    }
}
