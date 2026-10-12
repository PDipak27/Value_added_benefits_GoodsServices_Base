package com.vab.catalog.it;

import org.springframework.cache.CacheManager;
import com.vab.catalog.domain.Offer;
import com.vab.catalog.domain.OfferStatus;
import com.vab.events.common.ProductType;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Shared infra for catalog ITs: a real MongoDB 7 (the offer store, DD-16), as a static singleton.
 * Each subclass brings its own Redis (L2) so the outage test can stop its Redis without breaking the others.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractCatalogIntegrationTest {

    @SuppressWarnings("resource")
    static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7")).withReuse(true);

    static {
        MONGO.start();
    }

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("vab_catalog"));
    }

    /** A fresh, published offer code — tests never mutate the seeded catalog. */
    protected static String newCode() {
        return "IT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    protected static Offer offer(String code, String name, long amount) {
        return new Offer(code, name, "IT offer", ProductType.DIGITAL_SUBSCRIPTION, amount, "INR",
                "ps-" + code, OfferStatus.PUBLISHED, null, null, null, null);
    }

    /** The in-process Caffeine L1 of a named cache. */
    @SuppressWarnings("unchecked")
    protected static com.github.benmanes.caffeine.cache.Cache<Object, Object> l1(CacheManager manager, String cacheName) {
        return (com.github.benmanes.caffeine.cache.Cache<Object, Object>) manager.getCache(cacheName).getNativeCache();
    }
}
