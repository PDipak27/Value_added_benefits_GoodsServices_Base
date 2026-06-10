package com.vab.catalog.domain;

import com.vab.catalog.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Offer store (MongoDB, DD-16) with a two-tier read-cache: Caffeine L1 + Redis L2
 * (DD-17 / DD-18).
 *
 * <p>The two read paths are cached; absent offers are not cached
 * ({@code disableCachingNullValues} in {@link CacheConfig}), so misses always hit
 * Mongo. A hot read is served from the in-process L1 (no JSON deserialization);
 * L2 (Redis) is consulted only on an L1 miss. Writes through
 * {@link OfferAdminService} evict both caches.
 */
public interface OfferRepository extends MongoRepository<Offer, String> {

    @Cacheable(CacheConfig.OFFERS_BY_STATUS)
    List<Offer> findByStatus(OfferStatus status);

    @Override
    @Cacheable(CacheConfig.OFFER_BY_CODE)
    Optional<Offer> findById(String offerCode);
}
