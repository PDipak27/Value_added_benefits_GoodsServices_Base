package com.vab.catalog.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The fail-open contract (DD-20): every handler must <em>swallow</em> the cache
 * backend exception so the cached method falls through to its source (Mongo)
 * instead of failing the request when Redis is down.
 */
@ExtendWith(MockitoExtension.class)
class LoggingCacheErrorHandlerTest {

    @Mock Cache cache;

    private final LoggingCacheErrorHandler handler = new LoggingCacheErrorHandler();
    private final RuntimeException boom = new RuntimeException("redis down");

    @Test
    void get_put_evict_clear_errors_are_all_swallowed() {
        assertThatCode(() -> handler.handleCacheGetError(boom, cache, "k")).doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCachePutError(boom, cache, "k", "v")).doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCacheEvictError(boom, cache, "k")).doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCacheClearError(boom, cache)).doesNotThrowAnyException();
    }
}
