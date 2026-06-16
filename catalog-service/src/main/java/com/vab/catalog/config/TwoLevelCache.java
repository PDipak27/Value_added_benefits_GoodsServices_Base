package com.vab.catalog.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

/**
 * A near/far ({@code L1}/{@code L2}) {@link Cache} that fronts a shared Redis
 * cache with an in-process Caffeine cache (DD-18).
 *
 * <p><strong>Why:</strong> the catalog browse path returns a large list
 * (~5000 offers). Served from Redis alone, every request pays ~5000 JSON
 * deserializations. The L1 Caffeine layer holds the already-materialized objects
 * on the heap, so a hot read is a single map lookup — Redis (L2) is only touched
 * on an L1 miss.
 *
 * <p><strong>Read</strong> (L1 → L2 → loader): an L1 hit returns immediately; an
 * L1 miss falls through to L2 and, on an L2 hit, back-fills L1. <strong>Write</strong>
 * and <strong>evict/clear</strong> apply to <em>both</em> tiers so the local L1
 * never lags its own L2 after a local mutation.
 *
 * <p><strong>Cross-instance invalidation (DD-19):</strong> every {@code evict}/
 * {@code clear} also publishes a broadcast (via {@link CacheInvalidationPublisher})
 * so <em>other</em> instances clear their L1 near-immediately. The L1 TTL
 * (configurable, default 120s) remains a backstop for any broadcast that is missed (a peer that was down) or
 * any out-of-band Redis mutation. The local synchronous eviction plus the
 * broadcast means a write is reflected everywhere within a network hop, not a
 * TTL. The local-only {@link #clearLocalL1()} / {@link #evictLocalL1(Object)}
 * methods (used when <em>applying</em> a received broadcast) deliberately do
 * <strong>not</strong> re-publish — that would loop forever.
 */
public class TwoLevelCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    private final String name;
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> l1;
    private final Cache l2;
    private final CacheInvalidationPublisher publisher;

    public TwoLevelCache(String name,
                         com.github.benmanes.caffeine.cache.Cache<Object, Object> l1,
                         Cache l2,
                         CacheInvalidationPublisher publisher) {
        this.name      = name;
        this.l1        = l1;
        this.l2        = l2;
        this.publisher = publisher;
    }

    @Override
    public String getName() {
        return name;
    }

    /** Native cache is the L1 Caffeine instance (the hot path). */
    @Override
    public Object getNativeCache() {
        return l1;
    }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        Object v = l1.getIfPresent(key);
        if (v != null) {
            log.debug("Cache L1 HIT (cache={}, key={})", name, key);
            return new SimpleValueWrapper(v);
        }
        ValueWrapper l2Hit = l2.get(key);          // L1 miss → consult L2
        if (l2Hit != null) {
            Object val = l2Hit.get();
            if (val != null) {
                l1.put(key, val);                  // back-fill L1
            }
            log.debug("Cache L2 HIT, L1 backfilled (cache={}, key={})", name, key);
            return l2Hit;
        }
        log.debug("Cache MISS L1+L2 → loading from source (cache={}, key={})", name, key);
        return null;
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, @Nullable Class<T> type) {
        ValueWrapper wrapper = get(key);
        return wrapper == null ? null : (T) wrapper.get();
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object v = l1.getIfPresent(key);
        if (v != null) {
            log.debug("Cache L1 HIT (cache={}, key={})", name, key);
            return (T) v;
        }
        // L1 miss: delegate load-and-store to L2 (synchronized single-flight load
        // + Redis write on its own miss), then back-fill L1. We can't see whether
        // L2 hit or loaded from source from here — only that L1 missed.
        log.debug("Cache L1 MISS → L2/loader (cache={}, key={})", name, key);
        T loaded = l2.get(key, valueLoader);
        if (loaded != null) {
            l1.put(key, loaded);
        }
        return loaded;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        if (value != null) {
            // L1 first: if L2 (Redis) is down, the in-process L1 still gets warmed
            // and the L2 failure is swallowed by the CacheErrorHandler (DD-20).
            l1.put(key, value);
            l2.put(key, value);
        }
    }

    @Override
    @Nullable
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        ValueWrapper existing = get(key);
        if (existing != null) {
            return existing;
        }
        put(key, value);
        return null;
    }

    @Override
    public void evict(Object key) {
        l1.invalidate(key);
        l2.evict(key);
        publisher.publishEvict(name, key);
        log.debug("Cache EVICT L1+L2 + broadcast (cache={}, key={})", name, key);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        l1.invalidate(key);
        boolean evicted = l2.evictIfPresent(key);
        publisher.publishEvict(name, key);
        return evicted;
    }

    @Override
    public void clear() {
        l1.invalidateAll();
        l2.clear();
        publisher.publishClear(name);
        log.debug("Cache CLEAR L1+L2 + broadcast (cache={})", name);
    }

    @Override
    public boolean invalidate() {
        l1.invalidateAll();
        boolean hadEntries = l2.invalidate();
        publisher.publishClear(name);
        return hadEntries;
    }

    // ── Local-only invalidation (DD-19) ──────────────────────────────────────
    // Applied when handling a broadcast FROM a peer: touch this instance's L1
    // only — L2 was already cleared by the originator — and DO NOT re-publish.

    /** Clear this instance's entire L1 without touching L2 or re-broadcasting. */
    public void clearLocalL1() {
        l1.invalidateAll();
    }

    /** Evict a single key from this instance's L1 without touching L2 or re-broadcasting. */
    public void evictLocalL1(Object key) {
        l1.invalidate(key);
    }
}
