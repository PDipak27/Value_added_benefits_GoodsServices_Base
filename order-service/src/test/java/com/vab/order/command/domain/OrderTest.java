package com.vab.order.command.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    private static Order placed() {
        return Order.place("ord-1", "sub-1", "OFF-1", "PHYSICAL_GOOD",
                "px-1", 500, "INR", "PAY_NOW");
    }

    @Test
    void place_starts_in_placed_with_timestamp() {
        Order o = placed();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(o.getPlacedAt()).isNotNull();
        assertThat(o.getSubscriberId()).isEqualTo("sub-1");
    }

    @Test
    void confirm_moves_to_confirmed() {
        Order o = placed();
        Instant at = Instant.now();
        o.confirm(at);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.getConfirmedAt()).isEqualTo(at);
    }

    @Test
    void complete_is_terminal_and_carries_one_artifact() {
        Order o = placed();
        o.complete(Instant.now(), "TRK9", null, null);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(o.getTrackingRef()).isEqualTo("TRK9");
        assertThat(o.getActivationKey()).isNull();
    }

    @Test
    void fail_records_step_and_reason() {
        Order o = placed();
        o.fail("RESERVE_INVENTORY", "OUT_OF_STOCK");
        assertThat(o.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(o.getFailedStep()).isEqualTo("RESERVE_INVENTORY");
        assertThat(o.getFailureReason()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void requestCancel_flags_a_non_terminal_order() {
        Order o = placed();
        o.requestCancel();
        assertThat(o.isCancelRequested()).isTrue();
    }

    @Test
    void requestCancel_is_rejected_once_terminal() {
        Order o = placed();
        o.complete(Instant.now(), "TRK9", null, null); // COMPLETED is terminal
        org.assertj.core.api.Assertions.assertThatThrownBy(o::requestCancel)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancel_is_terminal_pre_pivot_nothing_charged() {
        Order o = placed();
        o.cancel("USER_CANCEL: before pivot");
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(o.getCancelledAt()).isNotNull();
        assertThat(o.getFailureReason()).isEqualTo("USER_CANCEL: before pivot");
    }

    @Test
    void cancelRefunded_is_terminal_post_pivot_forward_recovery() {
        Order o = placed();
        o.cancelRefunded("FULFIL_FAILED: DELIVERY_FAILED");
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED_REFUNDED);
        assertThat(o.getCancelledAt()).isNotNull();
        assertThat(o.getFailureReason()).isEqualTo("FULFIL_FAILED: DELIVERY_FAILED");
    }

    @Test
    void only_a_confirmed_digital_order_waits_on_provisioning() {
        Order digital = Order.place("ord-d", "sub-1", "OFF-d", "DIGITAL_SUBSCRIPTION", "px", 500, "INR", "PAY_NOW");
        Order physical = Order.place("ord-p", "sub-1", "OFF-p", "PHYSICAL_GOOD", "px", 500, "INR", "PAY_NOW");
        assertThat(digital.isWaitingOnProvisioning()).isFalse();   // PLACED: the saga's checkpoints handle cancel

        digital.confirm(Instant.now());
        physical.confirm(Instant.now());
        assertThat(digital.isWaitingOnProvisioning()).isTrue();
        assertThat(physical.isWaitingOnProvisioning()).isFalse();

        digital.complete(Instant.now(), null, null, "OTT-1");
        assertThat(digital.isWaitingOnProvisioning()).isFalse();
    }

    @Test
    void awaitPayment_shows_the_link_until_paid() {
        Order o = placed();
        Instant expires = Instant.parse("2026-11-16T10:08:00Z");

        o.awaitPayment("http://psp/checkout/pi_1?cs=x", expires);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(o.getCheckoutUrl()).isEqualTo("http://psp/checkout/pi_1?cs=x");
        assertThat(o.getPaymentExpiresAt()).isEqualTo(expires);
    }

    @Test
    void payment_is_pending_only_for_pay_now_before_confirmation() {
        Order payNow = placed();
        Order btm = Order.place("ord-b", "sub-1", "OFF-1", "PHYSICAL_GOOD", "px-1", 500, "INR", "BILL_TO_MOBILE");
        assertThat(payNow.isPaymentPending()).isTrue();    // PLACED: the intent may not exist yet
        assertThat(btm.isPaymentPending()).isFalse();

        payNow.awaitPayment("http://psp/checkout/pi_1?cs=x", Instant.now());
        assertThat(payNow.isPaymentPending()).isTrue();

        payNow.confirm(Instant.now());
        assertThat(payNow.isPaymentPending()).isFalse();
    }

    @Test
    void completes_with_external_ref_and_validity() {
        Order o = placed();
        Instant from = Instant.parse("2026-10-01T00:00:00Z"), until = Instant.parse("2027-04-01T00:00:00Z");
        o.complete(Instant.now(), null, null, "OTT-RD", from, until);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(o.getExternalRef()).isEqualTo("OTT-RD");
        assertThat(o.getValidFrom()).isEqualTo(from);
        assertThat(o.getValidUntil()).isEqualTo(until);
    }

    /** Entitlement revoke keeps the order COMPLETED and is idempotent: the first revoke time is kept. */
    @Test
    void revokeEntitlement_is_idempotent_and_keeps_order_completed() {
        Order o = placed();
        o.complete(Instant.now(), null, null, "OTT-1");
        Instant first = Instant.parse("2026-10-01T00:00:00Z");
        o.revokeEntitlement(first);
        o.revokeEntitlement(Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(o.isEntitlementRevoked()).isTrue();
        assertThat(o.getEntitlementRevokedAt()).isEqualTo(first);
    }
}
