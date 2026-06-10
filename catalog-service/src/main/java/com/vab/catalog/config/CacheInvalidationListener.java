package com.vab.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

/**
 * Redis pub/sub subscriber that applies a peer's {@link CacheInvalidationMessage}
 * to this instance's in-process L1 (DD-19).
 *
 * <p><strong>Skip-self:</strong> messages whose {@code senderId} equals this
 * instance's {@link InstanceId} are ignored — the originator already evicted its
 * L1 synchronously when it performed the write.
 *
 * <p>Only the <em>local L1</em> is touched here (via {@code clearLocalL1} /
 * {@code evictLocalL1}); the shared Redis L2 was already invalidated by the
 * originator, and crucially these local-only methods do <strong>not</strong>
 * re-publish — that would loop the broadcast indefinitely.
 */
public class CacheInvalidationListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationListener.class);

    private final TwoLevelCacheManager manager;
    private final ObjectMapper         mapper;
    private final InstanceId           instanceId;

    public CacheInvalidationListener(TwoLevelCacheManager manager,
                                     ObjectMapper mapper,
                                     InstanceId instanceId) {
        this.manager    = manager;
        this.mapper     = mapper;
        this.instanceId = instanceId;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            CacheInvalidationMessage msg =
                    mapper.readValue(message.getBody(), CacheInvalidationMessage.class);

            if (instanceId.value().equals(msg.senderId())) {
                return; // skip-self: we already evicted our own L1 synchronously
            }

            Cache cache = manager.getCache(msg.cacheName());
            if (cache instanceof TwoLevelCache tlc) {
                if (msg.clearAll()) {
                    tlc.clearLocalL1();
                } else {
                    tlc.evictLocalL1(msg.key());
                }
                log.debug("Applied peer cache-invalidation {} to local L1", msg);
            }
        } catch (Exception e) {
            log.warn("Failed to apply cache-invalidation message — local L1 will converge via TTL: {}",
                    e.toString());
        }
    }
}
