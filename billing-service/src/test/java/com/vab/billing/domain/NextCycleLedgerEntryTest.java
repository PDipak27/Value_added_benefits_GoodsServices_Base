package com.vab.billing.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NextCycleLedgerEntryTest {

    @Test
    void new_entry_is_pending() {
        NextCycleLedgerEntry e = new NextCycleLedgerEntry("ncl-1", "ord-1", "sub-1", 300, "INR");
        assertThat(e.getStatus()).isEqualTo(NextCycleLedgerEntry.Status.PENDING);
        assertThat(e.isReversed()).isFalse();
    }

    @Test
    void reverse_flips_to_reversed() {
        NextCycleLedgerEntry e = new NextCycleLedgerEntry("ncl-1", "ord-1", "sub-1", 300, "INR");
        e.reverse();
        assertThat(e.isReversed()).isTrue();
        assertThat(e.getStatus()).isEqualTo(NextCycleLedgerEntry.Status.REVERSED);
    }
}
