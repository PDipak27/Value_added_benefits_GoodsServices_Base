package com.vab.catalog.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.lang.Nullable;

/**
 * Makes the cache layer <strong>fail-open</strong> against an L2 (Redis) outage
 * (DD-20).
 *
 * <p>Spring's default {@code SimpleCacheErrorHandler} <em>rethrows</em> any
 * exception raised by the cache backend. With Redis down that turns every
 * cache-aside read (L1 miss → {@code l2.get}) and every post-write eviction
 * ({@code @CacheEvict} → {@code l2.clear}) into a {@code RedisConnectionFailureException}
 * that propagates to the caller — so the offer-browse and admin endpoints would
 * <em>fail</em> even though MongoDB (the source of truth) is perfectly healthy.
 *
 * <p>This handler logs at WARN and swallows instead, so the cached method falls
 * through to its source:
 * <ul>
 *   <li><strong>get</strong> — treated as a cache miss → Spring invokes the method
 *       → read served from Mongo.</li>
 *   <li><strong>put</strong> — value simply isn't cached this time → no effect on
 *       correctness.</li>
 *   <li><strong>evict / clear</strong> — the shared L2 couldn't be cleared; the
 *       local L1 eviction already ran in {@link TwoLevelCache} and the L1 TTL is
 *       the convergence backstop. The write itself (already committed to Mongo)
 *       still returns success.</li>
 * </ul>
 *
 * <p>The trade-off is bounded staleness, not lost data: during a Redis outage a
 * peer instance's L1 may serve offers up to its L1 TTL old. Acceptable for a
 * catalog that changes ≈twice a week (DD-17).
 */
public class LoggingCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache GET failed (cache={}, key={}) — falling through to source. cause={}",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, @Nullable Object value) {
        log.warn("Cache PUT failed (cache={}, key={}) — value not cached this time. cause={}",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache EVICT failed (cache={}, key={}) — relying on TTL backstop. cause={}",
                cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("Cache CLEAR failed (cache={}) — relying on TTL backstop. cause={}",
                cache.getName(), exception.toString());
    }
}
