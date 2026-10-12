package com.vab.catalog.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vab.catalog.config.CacheConfig;
import com.vab.catalog.config.CacheInvalidationMessage;
import com.vab.catalog.config.InstanceId;
import com.vab.catalog.config.TwoLevelCache;
import com.vab.catalog.domain.Offer;
import com.vab.catalog.domain.OfferStatus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two-level catalog cache on real infra (DD-17/18/19): Caffeine L1 → Redis L2 → Mongo. Read-through and
 * back-fill, JSON round-trip through Redis, evict-on-write across both tiers, and the Redis pub/sub
 * L1-invalidation broadcast (publish on write, apply a peer's message, skip our own).
 *
 * <p>"Out-of-band" Mongo edits (bypassing the admin API, so no eviction) are how a test proves which
 * tier served a read. Every test works on its own fresh offer code; Redis is flushed and L1 cleared first.
 */
class CatalogCacheIT extends AbstractCatalogIntegrationTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379).withReuse(true);

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
    @Autowired StringRedisTemplate redis;
    @Autowired CacheManager cacheManager;
    @Autowired InstanceId instanceId;
    @Autowired ObjectMapper objectMapper;

    private String code;

    @BeforeEach
    void freshState() {
        redis.execute((RedisCallback<Object>) c -> { c.serverCommands().flushAll(); return null; });
        List.of(CacheConfig.OFFER_BY_CODE, CacheConfig.OFFERS_BY_STATUS)
                .forEach(n -> ((TwoLevelCache) cacheManager.getCache(n)).clearLocalL1());
        code = newCode();
        mongo.save(offer(code, "Original", 100));
    }

    private StatefulRedisPubSubConnection<String, String> subscriber;
    private RedisClient pubSubClient;

    @AfterEach
    void closeSubscriber() {
        if (subscriber != null) subscriber.close();
        if (pubSubClient != null) pubSubClient.shutdown();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void renameInMongoOnly(String newName) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(code)), Update.update("name", newName), Offer.class);
    }

    private void getDetailExpectingName(String name) throws Exception {
        mvc.perform(get("/v1/offers/" + code)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name));
    }

    private boolean inL2(String cacheName, Object key) {
        return Boolean.TRUE.equals(redis.hasKey(cacheName + "::" + key));
    }

    private void publishInvalidation(String senderId, String cacheName, String key) throws Exception {
        redis.convertAndSend(CacheConfig.CACHE_INVALIDATION_CHANNEL,
                objectMapper.writeValueAsString(new CacheInvalidationMessage(senderId, cacheName, key)));
    }

    /** Raw Lettuce subscriber on the broadcast channel; SUBSCRIBE is synchronous, so nothing published afterwards is missed. */
    private BlockingQueue<CacheInvalidationMessage> listenToBroadcasts() {
        BlockingQueue<CacheInvalidationMessage> received = new LinkedBlockingQueue<>();
        pubSubClient = RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        subscriber = pubSubClient.connectPubSub();
        subscriber.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                try {
                    received.add(objectMapper.readValue(message, CacheInvalidationMessage.class));
                } catch (Exception ignored) {
                    // not our message shape
                }
            }
        });
        subscriber.sync().subscribe(CacheConfig.CACHE_INVALIDATION_CHANNEL);
        return received;
    }

    // ── read path ───────────────────────────────────────────────────────────

    /** A first detail read loads from Mongo and fills both tiers: the offer object in L1 and its JSON under "offerByCode::<code>" in Redis. */
    @Test
    void first_read_populates_l1_and_l2() throws Exception {
        getDetailExpectingName("Original");

        assertThat(l1(cacheManager, CacheConfig.OFFER_BY_CODE).getIfPresent(code)).isInstanceOf(Offer.class);
        assertThat(inL2(CacheConfig.OFFER_BY_CODE, code)).isTrue();
    }

    /** Hot path: a warm L1 serves the read without Mongo — an out-of-band Mongo edit is not visible yet. */
    @Test
    void warm_l1_serves_without_touching_mongo() throws Exception {
        getDetailExpectingName("Original");
        renameInMongoOnly("Changed out-of-band");

        getDetailExpectingName("Original");
    }

    /** L1 miss + L2 hit: the offer is deserialized from Redis JSON (typed round-trip) and back-filled into L1, still without Mongo. */
    @Test
    void l2_hit_round_trips_json_and_backfills_l1() throws Exception {
        getDetailExpectingName("Original");
        ((TwoLevelCache) cacheManager.getCache(CacheConfig.OFFER_BY_CODE)).clearLocalL1();
        renameInMongoOnly("Changed out-of-band");

        mvc.perform(get("/v1/offers/" + code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Original"))
                .andExpect(jsonPath("$.amount").value(100))
                .andExpect(jsonPath("$.productType").value("DIGITAL_SUBSCRIPTION"));
        assertThat(l1(cacheManager, CacheConfig.OFFER_BY_CODE).getIfPresent(code)).isInstanceOf(Offer.class);
    }

    /** Absent offers are never cached (null values are skipped), so a 404 leaves no L1 entry and no Redis key. */
    @Test
    void absent_offer_is_not_cached() throws Exception {
        String missing = newCode();

        mvc.perform(get("/v1/offers/" + missing)).andExpect(status().isNotFound());

        assertThat(l1(cacheManager, CacheConfig.OFFER_BY_CODE).getIfPresent(missing)).isNull();
        assertThat(inL2(CacheConfig.OFFER_BY_CODE, missing)).isFalse();
    }

    // ── write path: evict-on-write ──────────────────────────────────────────

    /** An admin update evicts both tiers of both caches, so the very next read returns the new price (no TTL wait). */
    @Test
    void admin_update_evicts_both_tiers_and_is_visible_immediately() throws Exception {
        getDetailExpectingName("Original");
        mvc.perform(get("/v1/offers")).andExpect(status().isOk());   // warm the browse cache too
        assertThat(inL2(CacheConfig.OFFERS_BY_STATUS, OfferStatus.PUBLISHED)).isTrue();

        mvc.perform(put("/v1/offers/" + code).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Repriced","description":"d","productType":"DIGITAL_SUBSCRIPTION",
                         "amount":250,"currency":"INR","priceSnapshotId":"ps-2"}"""))
                .andExpect(status().isOk());

        assertThat(l1(cacheManager, CacheConfig.OFFER_BY_CODE).getIfPresent(code)).isNull();
        assertThat(inL2(CacheConfig.OFFER_BY_CODE, code)).isFalse();
        assertThat(inL2(CacheConfig.OFFERS_BY_STATUS, OfferStatus.PUBLISHED)).isFalse();
        mvc.perform(get("/v1/offers/" + code)).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(250));
    }

    /** A newly created offer shows up in the (previously cached) browse list immediately, because create evicts offersByStatus. */
    @Test
    void admin_create_is_visible_in_cached_browse_list() throws Exception {
        String created = newCode();
        mvc.perform(get("/v1/offers")).andExpect(jsonPath("$[*].offerCode", not(hasItem(created))));

        mvc.perform(post("/v1/offers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"offerCode":"%s","name":"New","description":"d","productType":"DIGITAL_SUBSCRIPTION",
                         "amount":10,"currency":"INR","priceSnapshotId":"ps-new"}""".formatted(created)))
                .andExpect(status().isCreated());

        mvc.perform(get("/v1/offers")).andExpect(jsonPath("$[*].offerCode", hasItem(created)));
    }

    /** Withdrawing evicts too: the cached detail flips to 404 on the next read. */
    @Test
    void withdraw_evicts_and_detail_becomes_404() throws Exception {
        getDetailExpectingName("Original");

        mvc.perform(post("/v1/offers/" + code + ":withdraw")).andExpect(status().isOk());

        mvc.perform(get("/v1/offers/" + code)).andExpect(status().isNotFound());
    }

    // ── cross-instance broadcast (DD-19) ────────────────────────────────────

    /** Every eviction is broadcast on Redis pub/sub, stamped with this instance's id, as a clear-all for each evicted cache. */
    @Test
    void admin_write_broadcasts_invalidation_with_own_instance_id() throws Exception {
        BlockingQueue<CacheInvalidationMessage> broadcasts = listenToBroadcasts();

        mvc.perform(post("/v1/offers/" + code + ":withdraw")).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(5)).until(() -> broadcasts.size() >= 2);
        assertThat(broadcasts).allSatisfy(m -> {
            assertThat(m.senderId()).isEqualTo(instanceId.value());
            assertThat(m.clearAll()).isTrue();
        });
        assertThat(broadcasts).extracting(CacheInvalidationMessage::cacheName)
                .contains(CacheConfig.OFFER_BY_CODE, CacheConfig.OFFERS_BY_STATUS);
    }

    /** A peer's broadcast evicts the key from this instance's L1 only; the shared L2 entry is left alone (the peer already handled it). */
    @Test
    void peer_broadcast_evicts_local_l1_only() throws Exception {
        getDetailExpectingName("Original");

        publishInvalidation("peer-instance", CacheConfig.OFFER_BY_CODE, code);

        await().atMost(Duration.ofSeconds(5))
                .until(() -> l1(cacheManager, CacheConfig.OFFER_BY_CODE).getIfPresent(code) == null);
        assertThat(inL2(CacheConfig.OFFER_BY_CODE, code)).isTrue();
    }

    /** Skip-self: a broadcast carrying our own instance id is ignored, so the L1 entry survives (a later peer message proves delivery is working). */
    @Test
    void own_broadcast_is_ignored() throws Exception {
        getDetailExpectingName("Original");
        mvc.perform(get("/v1/offers")).andExpect(status().isOk());

        publishInvalidation(instanceId.value(), CacheConfig.OFFER_BY_CODE, code);
        publishInvalidation("peer-instance", CacheConfig.OFFERS_BY_STATUS, null);   // control message
        await().atMost(Duration.ofSeconds(5))
                .until(() -> l1(cacheManager, CacheConfig.OFFERS_BY_STATUS).asMap().isEmpty());
        Thread.sleep(300);   // listener dispatch may be concurrent; give the self message time to (not) apply

        assertThat(l1(cacheManager, CacheConfig.OFFER_BY_CODE).getIfPresent(code)).isNotNull();
    }
}
