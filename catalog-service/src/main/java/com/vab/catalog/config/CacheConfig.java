package com.vab.catalog.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Two-tier read-cache for the catalog: an in-process Caffeine L1 in front of a
 * shared Redis L2 (DD-18, building on the Redis cache of DD-17).
 *
 * <p><strong>Why two tiers.</strong> An offer-browse returns a large list
 * (~5000 offers). Served from Redis alone, every request pays ~5000 JSON
 * deserializations; the L1 Caffeine layer holds the already-materialized objects
 * on the heap, so a hot read is a single in-process lookup and L2 (Redis) is hit
 * only on an L1 miss. Both tiers use a short 15s TTL.
 *
 * <p><strong>Invalidation.</strong> Because the writer (the admin API) and the
 * cache live in the <em>same</em> service, invalidation needs no events: writes
 * evict inline via {@code @CacheEvict}
 * ({@link com.vab.catalog.domain.OfferAdminService}), clearing this instance's L1
 * <em>and</em> the shared L2. The short TTL is a backstop for any out-of-band
 * mutation (a manual {@code mongosh} edit, a re-seed) and bounds how long another
 * instance's L1 can lag after a write (≤ 15s).
 *
 * <p><strong>L2 serialization.</strong> Values are stored as JSON (not
 * JDK-serialized, so {@link com.vab.catalog.domain.Offer} need not implement
 * {@code Serializable}). Default typing is enabled so polymorphic returns
 * ({@code List<Offer>}, {@code Offer}) round-trip with their concrete types;
 * field-level visibility lets Jackson read the entity's private fields without
 * setters. The L1 holds live objects, so it serializes nothing.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Cache of the published-offer list ({@code findByStatus(PUBLISHED)}). */
    public static final String OFFERS_BY_STATUS = "offersByStatus";

    /** Cache of single offers keyed by {@code offerCode} ({@code findById}). */
    public static final String OFFER_BY_CODE = "offerByCode";

    /** Worst-case staleness for out-of-band edits; admin-API writes evict immediately. */
    private static final Duration TTL = Duration.ofSeconds(15);

    /** L1 (Caffeine) per-cache TTL — matches the L2 TTL. */
    private static final Duration L1_TTL = Duration.ofSeconds(15);

    /** L1 (Caffeine) per-cache entry cap — comfortably above the ~5000 offer codes. */
    private static final long L1_MAX_SIZE = 10_000;

    /**
     * The cache manager used by {@code @Cacheable}/{@code @CacheEvict}: a
     * {@link TwoLevelCacheManager} pairing a Caffeine L1 with a Redis L2 built
     * from {@link #catalogCacheConfiguration()}. Defining our own
     * {@code CacheManager} makes Spring Boot's cache auto-configuration back off.
     */
    @Bean
    CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheManager l2 = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(catalogCacheConfiguration())
                .build();
        return new TwoLevelCacheManager(l2, L1_TTL, L1_MAX_SIZE);
    }

    @Bean
    RedisCacheConfiguration catalogCacheConfiguration() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL)
                .disableCachingNullValues()  // don't cache 404s / absent offers
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer));
    }
}
