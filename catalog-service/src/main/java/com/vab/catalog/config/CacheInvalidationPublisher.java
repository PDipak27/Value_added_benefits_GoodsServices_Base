package com.vab.catalog.config;

/**
 * Broadcasts L1 (Caffeine) invalidations to peer catalog-service instances
 * (DD-19). Called by {@link TwoLevelCache} on every evict/clear so the
 * invalidation reaches other instances' in-process caches, not just the local
 * one and the shared Redis L2.
 */
public interface CacheInvalidationPublisher {

    /** Tell peers to clear their entire L1 for {@code cacheName}. */
    void publishClear(String cacheName);

    /** Tell peers to evict a single {@code key} from their L1 for {@code cacheName}. */
    void publishEvict(String cacheName, Object key);
}
