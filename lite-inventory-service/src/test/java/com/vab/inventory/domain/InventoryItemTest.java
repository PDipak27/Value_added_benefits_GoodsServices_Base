package com.vab.inventory.domain;

import com.vab.events.common.ProductType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryItemTest {

    private InventoryItem item(int total) {
        return new InventoryItem("OFF-p", ProductType.PHYSICAL_GOOD, total);
    }

    @Test
    void available_is_total_minus_reserved_minus_allocated() {
        InventoryItem i = item(10);
        i.reserve(3);
        i.allocate(2);
        assertThat(i.getAvailable()).isEqualTo(5);
    }

    @Test
    void canReserve_reflects_available() {
        InventoryItem i = item(2);
        assertThat(i.canReserve(2)).isTrue();
        assertThat(i.canReserve(3)).isFalse();
    }

    @Test
    void commit_moves_reserved_to_allocated() {
        InventoryItem i = item(10);
        i.reserve(4);
        i.commit(4);
        assertThat(i.getReserved()).isZero();
        assertThat(i.getAllocated()).isEqualTo(4);
    }

    @Test
    void release_methods_floor_at_zero() {
        InventoryItem i = item(10);
        i.reserve(1);
        // Releasing more than held must not drive the counter negative.
        i.releaseReserved(5);
        i.releaseAllocated(5);
        assertThat(i.getReserved()).isZero();
        assertThat(i.getAllocated()).isZero();
    }
}
