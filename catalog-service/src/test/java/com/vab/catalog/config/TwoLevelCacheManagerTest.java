package com.vab.catalog.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwoLevelCacheManagerTest {

    @Mock CacheManager l2Manager;
    @Mock Cache l2Cache;
    @Mock CacheInvalidationPublisher publisher;

    private TwoLevelCacheManager manager;

    @BeforeEach
    void setUp() {
        manager = new TwoLevelCacheManager(l2Manager, Duration.ofSeconds(120), 10000, publisher);
    }

    @Test
    void builds_a_two_level_cache_backed_by_the_l2_managers_cache() {
        when(l2Manager.getCache("offersByStatus")).thenReturn(l2Cache);

        Cache cache = manager.getCache("offersByStatus");

        assertThat(cache).isInstanceOf(TwoLevelCache.class);
        assertThat(cache.getName()).isEqualTo("offersByStatus");
    }

    @Test
    void caches_one_instance_per_name() {
        when(l2Manager.getCache("offersByStatus")).thenReturn(l2Cache);

        Cache first  = manager.getCache("offersByStatus");
        Cache second = manager.getCache("offersByStatus");

        // computeIfAbsent => same instance, and L2 manager consulted only once.
        assertThat(first).isSameAs(second);
        verify(l2Manager, times(1)).getCache("offersByStatus");
    }

    @Test
    void getCacheNames_reflects_created_caches() {
        when(l2Manager.getCache(anyString())).thenReturn(l2Cache);

        manager.getCache("offersByStatus");
        manager.getCache("offerByCode");

        assertThat(manager.getCacheNames()).containsExactlyInAnyOrder("offersByStatus", "offerByCode");
    }
}
