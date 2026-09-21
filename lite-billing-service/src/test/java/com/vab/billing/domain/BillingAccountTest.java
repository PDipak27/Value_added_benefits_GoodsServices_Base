package com.vab.billing.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BillingAccountTest {

    @Test
    void defaultFor_is_active_basic_with_1000_limit() {
        BillingAccount a = BillingAccount.defaultFor("sub-1");
        assertThat(a.isActive()).isTrue();
        assertThat(a.getPlanTier()).isEqualTo("BASIC");
        assertThat(a.getCreditLimit()).isEqualTo(1000);
        assertThat(a.getCurrentCycleBalance()).isZero();
    }

    @Test
    void canCharge_requires_active_and_amount_within_limit() {
        BillingAccount active = BillingAccount.defaultFor("sub-1");
        assertThat(active.canCharge(1000)).isTrue();  // at the limit
        assertThat(active.canCharge(1001)).isFalse(); // over the limit

        BillingAccount suspended = new BillingAccount("sub-2", BillingAccount.Status.SUSPENDED, "BASIC", 1000);
        assertThat(suspended.canCharge(1)).isFalse(); // inactive never charges
    }

    @Test
    void charge_and_release_move_the_cycle_balance() {
        BillingAccount a = BillingAccount.defaultFor("sub-1");
        a.charge(300);
        assertThat(a.getCurrentCycleBalance()).isEqualTo(300);
        a.release(100);
        assertThat(a.getCurrentCycleBalance()).isEqualTo(200);
    }
}
