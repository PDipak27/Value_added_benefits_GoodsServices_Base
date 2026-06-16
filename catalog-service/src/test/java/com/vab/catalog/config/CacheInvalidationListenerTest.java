package com.vab.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

import static org.mockito.Mockito.*;

/**
 * Tests the receive side of cross-instance L1 invalidation. A real
 * {@link ObjectMapper} deserializes the broadcast payload; the cache manager and
 * the target {@link TwoLevelCache} are mocked so we can assert which local-only
 * method is invoked. The skip-self rule is the critical case.
 */
@ExtendWith(MockitoExtension.class)
class CacheInvalidationListenerTest {

    @Mock TwoLevelCacheManager manager;
    @Mock TwoLevelCache cache;
    @Mock Message redisMessage;

    private final ObjectMapper mapper = new ObjectMapper();
    private CacheInvalidationListener listener;

    @BeforeEach
    void setUp() {
        // This instance's id is "me"; peer broadcasts come from "peer".
        listener = new CacheInvalidationListener(manager, mapper, new InstanceId("me"));
    }

    private void deliver(CacheInvalidationMessage msg) throws Exception {
        when(redisMessage.getBody()).thenReturn(mapper.writeValueAsBytes(msg));
        listener.onMessage(redisMessage, null);
    }

    @Test
    void peer_evict_clears_only_the_local_l1_for_that_key() throws Exception {
        when(manager.getCache("offersByStatus")).thenReturn(cache);

        deliver(new CacheInvalidationMessage("peer", "offersByStatus", "PUBLISHED"));

        verify(cache).evictLocalL1("PUBLISHED");
        verify(cache, never()).clearLocalL1();
    }

    @Test
    void peer_clear_all_clears_the_whole_local_l1() throws Exception {
        when(manager.getCache("offersByStatus")).thenReturn(cache);

        deliver(new CacheInvalidationMessage("peer", "offersByStatus", null));

        verify(cache).clearLocalL1();
        verify(cache, never()).evictLocalL1(any());
    }

    @Test
    void own_broadcast_is_skipped_entirely() throws Exception {
        // senderId == our instance id => we already evicted synchronously; ignore.
        deliver(new CacheInvalidationMessage("me", "offersByStatus", "PUBLISHED"));

        verifyNoInteractions(manager);
    }

    @Test
    void malformed_payload_is_swallowed() {
        when(redisMessage.getBody()).thenReturn("not-json".getBytes());
        // Must not throw — a bad broadcast must never break the subscriber thread.
        listener.onMessage(redisMessage, null);
        verifyNoInteractions(manager);
    }
}
