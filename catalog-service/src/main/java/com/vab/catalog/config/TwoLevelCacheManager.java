package com.vab.catalog.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link CacheManager} that pairs an L2 (Redis) cache manager with a per-cache L1
 * Caffeine cache, producing {@link TwoLevelCache} instances (DD-18).
 *
 * <p>Each named cache gets its own Caffeine instance with a short
 * {@code expireAfterWrite} TTL and a bounded maximum size — the L1 is a hot-set
 * accelerator, not a system of record. The L2 manager is the auto-built
 * {@code RedisCacheManager}; L2 lookups happen only on L1 misses.
 */
public class TwoLevelCacheManager implements CacheManager {

    private final CacheManager l2Manager;
    private final Duration     l1Ttl;
    private final long         l1MaxSize;

    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();

    public TwoLevelCacheManager(CacheManager l2Manager, Duration l1Ttl, long l1MaxSize) {
        this.l2Manager = l2Manager;
        this.l1Ttl     = l1Ttl;
        this.l1MaxSize = l1MaxSize;
    }

    @Override
    @Nullable
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name, n -> {
            Cache l2 = l2Manager.getCache(n);
            com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 =
                    Caffeine.newBuilder()
                            .expireAfterWrite(l1Ttl)
                            .maximumSize(l1MaxSize)
                            .build();
            return new TwoLevelCache(n, l1, l2);
        });
    }

    @Override
    public Collection<String> getCacheNames() {
        return Set.copyOf(caches.keySet());
    }
}
