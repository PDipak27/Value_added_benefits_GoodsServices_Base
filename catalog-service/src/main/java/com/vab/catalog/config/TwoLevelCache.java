package com.vab.catalog.config;

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
 * <p><strong>Cross-instance note:</strong> {@code evict}/{@code clear} clears this
 * instance's L1 and the shared L2; <em>other</em> instances' L1 copies expire on
 * their own short TTL (15s, DD-18). That bounded staleness is acceptable here —
 * catalog changes ≈ twice a week (same trade-off as DD-17's TTL backstop).
 */
public class TwoLevelCache implements Cache {

    private final String name;
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> l1;
    private final Cache l2;

    public TwoLevelCache(String name,
                         com.github.benmanes.caffeine.cache.Cache<Object, Object> l1,
                         Cache l2) {
        this.name = name;
        this.l1   = l1;
        this.l2   = l2;
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
            return new SimpleValueWrapper(v);
        }
        ValueWrapper l2Hit = l2.get(key);          // L1 miss → consult L2
        if (l2Hit != null) {
            Object val = l2Hit.get();
            if (val != null) {
                l1.put(key, val);                  // back-fill L1
            }
            return l2Hit;
        }
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
            return (T) v;
        }
        // Delegate load-and-store to L2 (handles the synchronized single-flight
        // load + Redis write), then back-fill L1 with the result.
        T loaded = l2.get(key, valueLoader);
        if (loaded != null) {
            l1.put(key, loaded);
        }
        return loaded;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        l2.put(key, value);
        if (value != null) {
            l1.put(key, value);
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
    }

    @Override
    public boolean evictIfPresent(Object key) {
        l1.invalidate(key);
        return l2.evictIfPresent(key);
    }

    @Override
    public void clear() {
        l1.invalidateAll();
        l2.clear();
    }

    @Override
    public boolean invalidate() {
        l1.invalidateAll();
        return l2.invalidate();
    }
}
