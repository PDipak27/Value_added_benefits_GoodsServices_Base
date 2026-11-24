package com.vab.billing.command;

import com.vab.billing.domain.*;
import com.vab.billing.payment.PaymentService;
import com.vab.events.billing.*;
import io.eventuate.tram.commands.consumer.CommandMessage;
import io.eventuate.tram.commands.consumer.CommandReplyToken;
import io.eventuate.tram.messaging.common.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Behaviour of the billing saga participant. BILL_TO_MOBILE: repositories are mocked; we assert on the
 * reply outcome/type and the account / next-cycle rows written via {@link ArgumentCaptor}. PAY_NOW is
 * delegated to {@link PaymentService} (tested in {@code PaymentServiceTest}). {@link CommandMessage} is
 * mocked to feed the command payload.
 */
@ExtendWith(MockitoExtension.class)
class BillingCommandHandlersTest {

    @Mock PaymentService payments;
    @Mock BillingAccountRepository accounts;
    @Mock NextCycleLedgerRepository nextCycle;

    private BillingCommandHandlers handlers;

    @BeforeEach
    void setUp() {
        handlers = new BillingCommandHandlers(payments, accounts, nextCycle);
    }

    @SuppressWarnings("unchecked")
    private static <C> CommandMessage<C> cmd(C command) {
        CommandMessage<C> cm = mock(CommandMessage.class);
        when(cm.getCommand()).thenReturn(command);
        return cm;
    }

    /** PAY_NOW lives in PaymentService (Design/15): the handlers only unwrap the command and pass it on. */
    @Nested
    class PayNowDelegation {

        @Test
        void create_intent_returns_the_payment_service_reply() {
            CreatePaymentIntentCommand c = new CreatePaymentIntentCommand("ord-1", "sub-1", 500, "INR");
            Message reply = mock(Message.class);
            when(payments.createIntent(c)).thenReturn(reply);

            assertThat(handlers.createPaymentIntent(cmd(c))).isSameAs(reply);
        }

        @Test
        void await_payment_hands_over_the_reply_token() {
            AwaitPaymentCommand c = new AwaitPaymentCommand("ord-1", "pi_1");
            CommandReplyToken token = new CommandReplyToken(Map.of(), "saga-reply");

            handlers.awaitPayment(cmd(c), token);

            verify(payments).awaitPayment(c, token);
        }

        @Test
        void cancel_void_capture_and_refund_are_delegated() {
            handlers.cancelPayment(cmd(new CancelPaymentCommand("ord-1", "USER_CANCEL")));
            handlers.voidPayment(cmd(new VoidPaymentCommand("pi_1", "rollback")));
            CaptureBillingCommand capture = new CaptureBillingCommand("pi_1", 500, "INR");
            handlers.capture(cmd(capture));
            RefundBillingCommand refund = new RefundBillingCommand("pi_1", 500, "INR", "USER_CANCEL");
            handlers.refund(cmd(refund));

            verify(payments).cancelPayment("ord-1", "USER_CANCEL");
            verify(payments).voidPayment("pi_1", "rollback");
            verify(payments).capture(capture);
            verify(payments).refund(refund);
        }
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
