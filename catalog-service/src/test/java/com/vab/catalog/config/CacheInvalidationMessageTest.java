package com.vab.catalog.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheInvalidationMessageTest {

    @Test
    void clearAll_true_when_key_null_or_empty() {
        assertThat(new CacheInvalidationMessage("s", "c", null).clearAll()).isTrue();
        assertThat(new CacheInvalidationMessage("s", "c", "").clearAll()).isTrue();
    }

    @Test
    void clearAll_false_when_key_present() {
        assertThat(new CacheInvalidationMessage("s", "c", "PUBLISHED").clearAll()).isFalse();
    }
}
