package com.vab.catalog.config;

/**
 * Payload broadcast over Redis pub/sub to invalidate the in-process Caffeine L1
 * on <em>other</em> catalog-service instances (DD-19).
 *
 * @param senderId  the {@link InstanceId} of the publishing instance — receivers
 *                  ignore messages where {@code senderId == their own id}
 *                  (skip-self: the originator already evicted its L1 locally).
 * @param cacheName the affected cache ({@code offersByStatus} / {@code offerByCode}).
 * @param key       the affected key as a string, or {@code null}/empty for a
 *                  whole-cache clear ({@code allEntries}).
 */
public record CacheInvalidationMessage(String senderId, String cacheName, String key) {

    /** True when the whole named cache should be cleared (no specific key). */
    public boolean clearAll() {
        return key == null || key.isEmpty();
    }
}
