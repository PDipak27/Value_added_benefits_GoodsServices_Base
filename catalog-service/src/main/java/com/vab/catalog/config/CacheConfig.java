package com.vab.catalog.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Redis read-cache for the catalog (DD-17).
 *
 * <p>The catalog is read-heavy and changes at most a couple of times a week.
 * Because the writer (the admin API) and the cache live in the <em>same</em>
 * service, invalidation needs no events: writes evict inline via
 * {@code @CacheEvict} ({@link com.vab.catalog.domain.OfferAdminService}), and a
 * short TTL is a backstop for any out-of-band mutation (a manual {@code mongosh}
 * edit, a re-seed). Because the cache is <em>shared</em> Redis, evict-on-write is
 * correct across every catalog instance with no distributed coordination.
 *
 * <p>Values are stored as JSON (not JDK-serialized, so {@link
 * com.vab.catalog.domain.Offer} need not implement {@code Serializable}). Default
 * typing is enabled so polymorphic returns ({@code List<Offer>}, {@code Offer})
 * round-trip with their concrete types; field-level visibility lets Jackson read
 * the entity's private fields without setters.
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
