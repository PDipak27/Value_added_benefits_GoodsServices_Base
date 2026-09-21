package com.vab.billing.command;

import com.vab.billing.domain.*;
import com.vab.billing.domain.BillingLedgerEntry.Status;
import com.vab.billing.domain.BillingLedgerEntry.Type;
import com.vab.events.billing.*;
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
import static org.mockito.Mockito.*;

/**
 * Behaviour of the billing saga participant. Repositories are mocked; we assert
 * on the reply outcome/type (PAY_NOW vs BILL_TO_MOBILE flows) and on the ledger /
 * account rows written via {@link ArgumentCaptor}. {@link CommandMessage} is
 * mocked to feed the command payload.
 */
@ExtendWith(MockitoExtension.class)
class BillingCommandHandlersTest {

    @Mock BillingLedgerRepository ledger;
    @Mock BillingAccountRepository accounts;
    @Mock NextCycleLedgerRepository nextCycle;

    private BillingCommandHandlers handlers;

    @BeforeEach
    void setUp() {
        handlers = new BillingCommandHandlers(ledger, accounts, nextCycle);
    }

    @SuppressWarnings("unchecked")
    private static <C> CommandMessage<C> cmd(C command) {
        CommandMessage<C> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(command);
        return cm;
    }

    private BillingLedgerEntry savedLedger() {
        ArgumentCaptor<BillingLedgerEntry> c = ArgumentCaptor.forClass(BillingLedgerEntry.class);
        verify(ledger).save(c.capture());
        return c.getValue();
    }

    @Nested
    class Authorize {

        @Test
        void within_limit_authorizes_and_writes_authorized_ledger_row() {
            Message reply = handlers.authorize(cmd(
                    new AuthorizeBillingCommand("ord-1", "sub-1", 500, "INR", "PAY_NOW")));

            BillingAuthorized body = Replies.assertSuccess(reply, BillingAuthorized.class);
            assertThat(body.getAuthId()).startsWith("auth_");
            BillingLedgerEntry row = savedLedger();
            assertThat(row.getType()).isEqualTo(Type.AUTHORIZE);
            assertThat(row.getStatus()).isEqualTo(Status.AUTHORIZED);
        }

        @Test
        void above_threshold_declines_with_failure_and_declined_row() {
            // 1000 > DECLINE_THRESHOLD (999) => declined.
            Message reply = handlers.authorize(cmd(
                    new AuthorizeBillingCommand("ord-1", "sub-1", 1000, "INR", "PAY_NOW")));

            BillingDeclined body = Replies.assertFailure(reply, BillingDeclined.class);
            assertThat(body.getReason()).isEqualTo("AMOUNT_EXCEEDS_LIMIT");
            assertThat(savedLedger().getStatus()).isEqualTo(Status.DECLINED);
        }
    }

    @Nested
    class Capture {

        @Test
        void normal_amount_captures() {
            Message reply = handlers.capture(cmd(new CaptureBillingCommand("auth_x", 500, "INR")));

            BillingCaptured body = Replies.assertSuccess(reply, BillingCaptured.class);
            assertThat(body.getCaptureId()).startsWith("cap_");
            BillingLedgerEntry row = savedLedger();
            assertThat(row.getType()).isEqualTo(Type.CAPTURE);
            assertThat(row.getStatus()).isEqualTo(Status.CAPTURED);
        }

        @Test
        void magic_777_hard_declines_the_pivot_with_failure() {
            // DD-26: capture is the pivot. A hard decline replies withFailure — the
            // pivot did not commit, so the saga rolls back to FAILED (no refund).
            Message reply = handlers.capture(cmd(new CaptureBillingCommand("auth_x", 777, "INR")));

            BillingCaptureFailed body = Replies.assertFailure(reply, BillingCaptureFailed.class);
            assertThat(body.getReason()).isEqualTo("CAPTURE_DECLINED");
            assertThat(savedLedger().getStatus()).isEqualTo(Status.DECLINED);
        }
    }

    @Test
    void refund_writes_refunded_row_and_replies_refunded() {
        Message reply = handlers.refund(cmd(new RefundBillingCommand("auth_x", 500, "INR", "compensation")));

        Replies.assertSuccess(reply, BillingRefunded.class);
        BillingLedgerEntry row = savedLedger();
        assertThat(row.getType()).isEqualTo(Type.REFUND);
        assertThat(row.getStatus()).isEqualTo(Status.REFUNDED);
    }

    @Nested
    class CheckAccountLimit {

        @Test
        void auto_provisions_default_account_when_unknown_then_passes() {
            when(accounts.findByIdForUpdate("sub-new")).thenReturn(Optional.empty());
            when(accounts.save(any(BillingAccount.class))).thenAnswer(i -> i.getArgument(0));

            Message reply = handlers.checkAccountLimit(cmd(
                    new CheckAccountLimitCommand("sub-new", 500, "INR")));

            Replies.assertSuccess(reply, AccountLimitOk.class);
            verify(accounts).save(any(BillingAccount.class)); // default account persisted
        }

        @Test
        void suspended_account_fails_with_account_suspended() {
            when(accounts.findByIdForUpdate("sub-1")).thenReturn(Optional.of(
                    new BillingAccount("sub-1", BillingAccount.Status.SUSPENDED, "BASIC", 1000)));

            Message reply = handlers.checkAccountLimit(cmd(
                    new CheckAccountLimitCommand("sub-1", 100, "INR")));

            assertThat(Replies.assertFailure(reply, AccountLimitExceeded.class).getReason())
                    .isEqualTo("ACCOUNT_SUSPENDED");
        }

        @Test
        void amount_over_credit_limit_fails_with_credit_limit_exceeded() {
            when(accounts.findByIdForUpdate("sub-1")).thenReturn(Optional.of(
                    BillingAccount.defaultFor("sub-1"))); // limit 1000

            Message reply = handlers.checkAccountLimit(cmd(
                    new CheckAccountLimitCommand("sub-1", 1001, "INR")));

            assertThat(Replies.assertFailure(reply, AccountLimitExceeded.class).getReason())
                    .isEqualTo("CREDIT_LIMIT_EXCEEDED");
        }
    }

    @Nested
    class AppendToLedger {

        @Test
        void persists_pending_entry_and_charges_account() {
            BillingAccount acct = BillingAccount.defaultFor("sub-1");
            when(accounts.findByIdForUpdate("sub-1")).thenReturn(Optional.of(acct));

            Message reply = handlers.appendToLedger(cmd(
                    new AppendToLedgerCommand("ord-1", "sub-1", 300, "INR")));

            Replies.assertSuccess(reply, LedgerAppended.class);
            ArgumentCaptor<NextCycleLedgerEntry> c = ArgumentCaptor.forClass(NextCycleLedgerEntry.class);
            verify(nextCycle).save(c.capture());
            assertThat(c.getValue().getStatus()).isEqualTo(NextCycleLedgerEntry.Status.PENDING);
            assertThat(acct.getCurrentCycleBalance()).isEqualTo(300); // charged
        }
    }

    @Nested
    class ReverseLedger {

        @Test
        void reverses_pending_entry_and_releases_account_balance() {
            NextCycleLedgerEntry entry = new NextCycleLedgerEntry("ncl-1", "ord-1", "sub-1", 300, "INR");
            BillingAccount acct = BillingAccount.defaultFor("sub-1");
            acct.charge(300);
            when(nextCycle.findById("ncl-1")).thenReturn(Optional.of(entry));
            when(accounts.findByIdForUpdate("sub-1")).thenReturn(Optional.of(acct));

            Message reply = handlers.reverseLedger(cmd(new ReverseLedgerCommand("ncl-1", "compensation")));

            Replies.assertSuccess(reply, LedgerReversed.class);
            assertThat(entry.isReversed()).isTrue();
            assertThat(acct.getCurrentCycleBalance()).isZero(); // released
        }

        @Test
        void unknown_entry_is_a_no_op_success() {
            when(nextCycle.findById("missing")).thenReturn(Optional.empty());

            Message reply = handlers.reverseLedger(cmd(new ReverseLedgerCommand("missing", "compensation")));

            Replies.assertSuccess(reply, LedgerReversed.class);
            verify(nextCycle, never()).save(any());
        }

        @Test
        void already_reversed_entry_is_not_reversed_again() {
            NextCycleLedgerEntry entry = new NextCycleLedgerEntry("ncl-1", "ord-1", "sub-1", 300, "INR");
            entry.reverse();
            when(nextCycle.findById("ncl-1")).thenReturn(Optional.of(entry));

            Message reply = handlers.reverseLedger(cmd(new ReverseLedgerCommand("ncl-1", "compensation")));

            Replies.assertSuccess(reply, LedgerReversed.class);
            verify(nextCycle, never()).save(any());
            verify(accounts, never()).save(any());
        }
    }
}
