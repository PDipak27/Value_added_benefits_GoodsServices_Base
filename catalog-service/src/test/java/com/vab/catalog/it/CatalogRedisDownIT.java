package com.vab.catalog.it;

import com.vab.catalog.config.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-open cache (DD-20): the app boots with Redis up, then its private Redis is stopped. Reads and
 * admin writes must keep working off Mongo (LoggingCacheErrorHandler swallows L2 errors) instead of 500s.
 * Own Redis container (not reused) + {@link DirtiesContext}, so the dead-Redis context never leaks
 * into other ITs.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogRedisDownIT extends AbstractCatalogIntegrationTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired CacheManager cacheManager;

    /** Context is already up (Redis was reachable at boot); take L2 away before each test. */
    @BeforeEach
    void stopRedis() {
        if (REDIS.isRunning()) REDIS.stop();
    }

    /** With Redis down, an uncached detail read falls through to Mongo (200, not 500) and still warms the in-process L1. */
    @Test
    void detail_read_fails_open_to_mongo_and_warms_l1() throws Exception {
        String code = newCode();
        mongo.save(offer(code, "Served from Mongo", 100));

        mvc.perform(get("/v1/offers/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Served from Mongo"));

        assertThat(l1(cacheManager, CacheConfig.OFFER_BY_CODE).getIfPresent(code)).isNotNull();
    }

    /** With Redis down, the browse list is still served from Mongo (seeded catalog is returned). */
    @Test
    void browse_fails_open_to_mongo() throws Exception {
        mvc.perform(get("/v1/offers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    /** With Redis down, an admin update still succeeds (L2 evict error swallowed) and the local L1 eviction makes the new value visible. */
    @Test
    void admin_write_succeeds_and_is_visible_with_redis_down() throws Exception {
        String code = newCode();
        mongo.save(offer(code, "Before", 100));
        mvc.perform(get("/v1/offers/" + code)).andExpect(jsonPath("$.amount").value(100));   // warm L1

        mvc.perform(put("/v1/offers/" + code).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"After","description":"d","productType":"DIGITAL_SUBSCRIPTION",
                         "amount":300,"currency":"INR","priceSnapshotId":"ps-after"}"""))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/offers/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(300));
    }
}
