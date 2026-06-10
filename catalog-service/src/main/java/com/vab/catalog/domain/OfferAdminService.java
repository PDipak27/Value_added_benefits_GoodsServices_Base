package com.vab.catalog.domain;

import com.vab.catalog.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Write side of the catalog (DD-17 / DD-18). Every mutation evicts both
 * read-caches inline — clearing this instance's Caffeine L1 <em>and</em> the
 * shared Redis L2 — so a freshly published / re-priced / withdrawn offer is
 * visible on the next read here immediately and on other instances within the
 * short L1 TTL (≤ 15s). No events, no relay. Catalog writes are rare
 * (≈ twice a week), so flushing the whole small cache ({@code allEntries = true})
 * rather than computing per-key evictions is the simpler, cheaper choice.
 *
 * <p>Catalog <em>domain</em> events (OfferPublished / PriceChanged) for
 * cross-service consumers (e.g. the Order projector's price snapshots) are a
 * separate, deferred decision — they are not needed for cache invalidation.
 */
@Service
public class OfferAdminService {

    private static final Logger log = LoggerFactory.getLogger(OfferAdminService.class);

    private final OfferRepository offers;

    public OfferAdminService(OfferRepository offers) {
        this.offers = offers;
    }

    /** Create or replace an offer, then evict the caches. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.OFFERS_BY_STATUS, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.OFFER_BY_CODE,    allEntries = true)
    })
    public Offer upsert(Offer offer) {
        Offer saved = offers.save(offer);
        log.info("Offer upserted: {} (status={}) — catalog caches evicted",
                saved.getOfferCode(), saved.getStatus());
        return saved;
    }

    /** Withdraw an offer if it exists; evicts the caches. Empty if not found. */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.OFFERS_BY_STATUS, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.OFFER_BY_CODE,    allEntries = true)
    })
    public Optional<Offer> withdraw(String offerCode) {
        return offers.findById(offerCode).map(offer -> {
            offer.withdraw();
            Offer saved = offers.save(offer);
            log.info("Offer withdrawn: {} — catalog caches evicted", saved.getOfferCode());
            return saved;
        });
    }
}
