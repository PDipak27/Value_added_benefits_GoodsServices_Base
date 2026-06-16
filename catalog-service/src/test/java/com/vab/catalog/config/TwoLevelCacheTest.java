package com.vab.catalog.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the near/far cache. The L1 tier is a <em>real</em> Caffeine
 * instance (a plain in-process map — not an external resource), so we exercise
 * genuine L1 behaviour while mocking the L2 Spring {@link Cache} and the
 * cross-instance {@link CacheInvalidationPublisher}.
 */
@ExtendWith(MockitoExtension.class)
class TwoLevelCacheTest {

    @Mock Cache l2;
    @Mock CacheInvalidationPublisher publisher;

    private com.github.benmanes.caffeine.cache.Cache<Object, Object> l1;
    private TwoLevelCache cache;

    @BeforeEach
    void setUp() {
        l1 = Caffeine.newBuilder().build();
        cache = new TwoLevelCache("offersByStatus", l1, l2, publisher);
    }

    @Nested
    class Get {

        @Test
        void l1_hit_returns_without_consulting_l2() {
            l1.put("k", "v");

            Cache.ValueWrapper result = cache.get("k");

            assertThat(result).isNotNull();
            assertThat(result.get()).isEqualTo("v");
            verifyNoInteractions(l2);
        }

        @Test
        void l1_miss_then_l2_hit_backfills_l1() {
            when(l2.get("k")).thenReturn(new SimpleValueWrapper("v"));

            Cache.ValueWrapper result = cache.get("k");

            assertThat(result.get()).isEqualTo("v");
            // L2 hit must populate L1 so the next read is a local hit.
            assertThat(l1.getIfPresent("k")).isEqualTo("v");
        }

        @Test
        void l1_miss_and_l2_miss_returns_null() {
            when(l2.get("k")).thenReturn(null);
            assertThat(cache.get("k")).isNull();
            assertThat(l1.getIfPresent("k")).isNull();
        }
    }

    @Nested
    class GetWithLoader {

        @Test
        void l1_hit_skips_loader_and_l2() throws Exception {
            l1.put("k", "v");
            @SuppressWarnings("unchecked")
            Callable<String> loader = mock(Callable.class);

            String result = cache.get("k", loader);

            assertThat(result).isEqualTo("v");
            verifyNoInteractions(l2, loader);
        }

        @Test
        void l1_miss_delegates_to_l2_loader_and_backfills_l1() {
            when(l2.get(eq("k"), org.mockito.ArgumentMatchers.<Callable<String>>any()))
                    .thenReturn("loaded");

            String result = cache.get("k", () -> "loaded");

            assertThat(result).isEqualTo("loaded");
            assertThat(l1.getIfPresent("k")).isEqualTo("loaded");
        }
    }

    @Nested
    class Put {

        @Test
        void writes_both_tiers() {
            cache.put("k", "v");
            assertThat(l1.getIfPresent("k")).isEqualTo("v");
            verify(l2).put("k", "v");
        }

        @Test
        void null_value_is_not_written_to_either_tier() {
            cache.put("k", null);
            assertThat(l1.getIfPresent("k")).isNull();
            verifyNoInteractions(l2);
        }
    }

    @Nested
    class EvictAndClear {

        @Test
        void evict_clears_both_tiers_and_broadcasts() {
            l1.put("k", "v");

            cache.evict("k");

            assertThat(l1.getIfPresent("k")).isNull();
            verify(l2).evict("k");
            verify(publisher).publishEvict("offersByStatus", "k");
        }

        @Test
        void clear_clears_both_tiers_and_broadcasts() {
            l1.put("k", "v");

            cache.clear();

            assertThat(l1.getIfPresent("k")).isNull();
            verify(l2).clear();
            verify(publisher).publishClear("offersByStatus");
        }
    }

    @Nested
    class LocalOnlyInvalidation {

        // Applied when handling a peer broadcast: touch L1 only, never L2, never re-publish.

        @Test
        void clearLocalL1_does_not_touch_l2_or_publish() {
            l1.put("k", "v");

            cache.clearLocalL1();

            assertThat(l1.getIfPresent("k")).isNull();
            verifyNoInteractions(l2, publisher);
        }

        @Test
        void evictLocalL1_does_not_touch_l2_or_publish() {
            l1.put("k", "v");

            cache.evictLocalL1("k");

            assertThat(l1.getIfPresent("k")).isNull();
            verifyNoInteractions(l2, publisher);
        }
    }
}
