package com.vab.catalog.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.UUID;

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
 * cache live in the <em>same</em> service, invalidation needs no domain events:
 * writes evict inline via {@code @CacheEvict}
 * ({@link com.vab.catalog.domain.OfferAdminService}), clearing this instance's L1
 * <em>and</em> the shared L2. To clear the L1 of <em>other</em> instances, every
 * eviction also publishes a Redis pub/sub broadcast (DD-19): peers subscribe and
 * clear their L1 near-immediately, skipping their own messages via an
 * {@link InstanceId}. Because the broadcast is wired into the cache layer
 * ({@link TwoLevelCache}), it fires for <em>any</em> eviction — not only the
 * admin API. The short TTL remains a backstop for a missed broadcast (a peer that
 * was down) or an out-of-band Redis mutation.
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
public class CacheConfig implements CachingConfigurer {

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

    /** Redis pub/sub channel for cross-instance L1 invalidation broadcasts (DD-19). */
    public static final String CACHE_INVALIDATION_CHANNEL = "catalog:cache:invalidate";

    /**
     * The cache manager used by {@code @Cacheable}/{@code @CacheEvict}: a
     * {@link TwoLevelCacheManager} pairing a Caffeine L1 with a Redis L2 built
     * from {@link #catalogCacheConfiguration()}. Defining our own
     * {@code CacheManager} makes Spring Boot's cache auto-configuration back off.
     */
    @Bean
    CacheManager cacheManager(RedisConnectionFactory connectionFactory,
                              CacheInvalidationPublisher cacheInvalidationPublisher) {
        RedisCacheManager l2 = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(catalogCacheConfiguration())
                .build();
        return new TwoLevelCacheManager(l2, L1_TTL, L1_MAX_SIZE, cacheInvalidationPublisher);
    }

    /**
     * Makes the cache layer fail-open if Redis (L2) is unreachable (DD-20):
     * cache errors are logged and swallowed so {@code @Cacheable} reads fall
     * through to Mongo and {@code @CacheEvict} writes still return success. This
     * overrides Spring's default {@code SimpleCacheErrorHandler}, which rethrows.
     */
    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    /** Unique id for this instance — stamped into broadcasts so receivers can skip-self (DD-19). */
    @Bean
    InstanceId catalogInstanceId() {
        return new InstanceId(UUID.randomUUID().toString());
    }

    /** Publishes L1-invalidation broadcasts over Redis pub/sub (DD-19). */
    @Bean
    CacheInvalidationPublisher cacheInvalidationPublisher(StringRedisTemplate redisTemplate,
                                                          ObjectMapper objectMapper,
                                                          InstanceId catalogInstanceId) {
        return new RedisCacheInvalidationPublisher(
                redisTemplate, objectMapper, CACHE_INVALIDATION_CHANNEL, catalogInstanceId);
    }

    /**
     * Subscribes to the invalidation channel and clears this instance's L1 when a
     * <em>peer</em> evicts (skip-self via {@link InstanceId}) — DD-19.
     */
    @Bean
    RedisMessageListenerContainer cacheInvalidationListenerContainer(RedisConnectionFactory connectionFactory,
                                                                     CacheManager cacheManager,
                                                                     ObjectMapper objectMapper,
                                                                     InstanceId catalogInstanceId) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        CacheInvalidationListener listener = new CacheInvalidationListener(
                (TwoLevelCacheManager) cacheManager, objectMapper, catalogInstanceId);
        container.addMessageListener(listener, new ChannelTopic(CACHE_INVALIDATION_CHANNEL));
        return container;
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
