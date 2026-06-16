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
}
