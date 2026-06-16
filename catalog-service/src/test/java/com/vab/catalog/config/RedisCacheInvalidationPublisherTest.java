package com.vab.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests the publish side of cross-instance invalidation. A real
 * {@link ObjectMapper} serializes the payload (so we verify the wire format),
 * and {@link StringRedisTemplate} is mocked to capture what gets sent.
 */
@ExtendWith(MockitoExtension.class)
class RedisCacheInvalidationPublisherTest {

    @Mock StringRedisTemplate redis;
    @Captor ArgumentCaptor<String> payload;

    private final ObjectMapper mapper = new ObjectMapper();
    private RedisCacheInvalidationPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RedisCacheInvalidationPublisher(redis, mapper, "catalog:cache:invalidate", new InstanceId("me"));
    }

    @Test
    void publishEvict_sends_keyed_message_stamped_with_instance_id() throws Exception {
        publisher.publishEvict("offerByCode", "OFF-1");

        verify(redis).convertAndSend(eq("catalog:cache:invalidate"), payload.capture());
        CacheInvalidationMessage sent = mapper.readValue(payload.getValue(), CacheInvalidationMessage.class);
        assertThat(sent.senderId()).isEqualTo("me");
        assertThat(sent.cacheName()).isEqualTo("offerByCode");
        assertThat(sent.key()).isEqualTo("OFF-1");
        assertThat(sent.clearAll()).isFalse();
    }

    @Test
    void publishClear_sends_message_with_null_key() throws Exception {
        publisher.publishClear("offersByStatus");

        verify(redis).convertAndSend(eq("catalog:cache:invalidate"), payload.capture());
        CacheInvalidationMessage sent = mapper.readValue(payload.getValue(), CacheInvalidationMessage.class);
        assertThat(sent.key()).isNull();
        assertThat(sent.clearAll()).isTrue();
    }

    @Test
    void redis_failure_is_swallowed_not_propagated() {
        doThrow(new RuntimeException("redis down")).when(redis).convertAndSend(anyString(), anyString());
        // A broadcast hiccup must never fail the write that triggered the eviction.
        publisher.publishClear("offersByStatus");
    }
}
