package com.vab.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link CacheInvalidationPublisher} backed by Redis pub/sub (DD-19). Publishes a
 * JSON {@link CacheInvalidationMessage} (stamped with this instance's
 * {@link InstanceId}) to a shared channel; every catalog-service instance
 * subscribes and clears its L1 accordingly (skipping its own messages).
 *
 * <p>Pub/sub is fire-and-forget (at-most-once): an instance that is down when a
 * message is sent simply misses it and instead converges via the 15s L1 TTL
 * backstop. Publishing failures are logged, never thrown — a broadcast hiccup
 * must not fail the write that triggered the eviction.
 */
public class RedisCacheInvalidationPublisher implements CacheInvalidationPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheInvalidationPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper        mapper;
    private final String              channel;
    private final InstanceId          instanceId;

    public RedisCacheInvalidationPublisher(StringRedisTemplate redis,
                                           ObjectMapper mapper,
                                           String channel,
                                           InstanceId instanceId) {
        this.redis      = redis;
        this.mapper     = mapper;
        this.channel    = channel;
        this.instanceId = instanceId;
    }

    @Override
    public void publishClear(String cacheName) {
        publish(new CacheInvalidationMessage(instanceId.value(), cacheName, null));
    }

    @Override
    public void publishEvict(String cacheName, Object key) {
        publish(new CacheInvalidationMessage(
                instanceId.value(), cacheName, key == null ? null : String.valueOf(key)));
    }

    private void publish(CacheInvalidationMessage msg) {
        try {
            redis.convertAndSend(channel, mapper.writeValueAsString(msg));
            log.debug("Published cache-invalidation {}", msg);
        } catch (Exception e) {
            log.warn("Failed to publish cache-invalidation {} — peers will converge via L1 TTL: {}",
                    msg, e.toString());
        }
    }
}
