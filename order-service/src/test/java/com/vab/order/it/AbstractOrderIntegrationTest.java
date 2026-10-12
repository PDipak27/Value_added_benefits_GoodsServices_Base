package com.vab.order.it;

import com.vab.events.common.EventuateJackson;
import com.vab.order.command.catalog.CatalogClient;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Shared infra for order-service full-context ITs: Postgres 18 (seeded with the 8 Tram outbox/saga
 * tables, as compose does), MongoDB 7 (read models) and Kafka (Tram consumers must subscribe).
 * Static singletons; every subclass shares ONE Spring context (same annotations + mocks here).
 *
 * <p>No CDC runs, so nothing relays the outbox: ITs assert this service's own state, outbox rows and
 * read models — saga completion across services is covered by e2e. Keycloak is replaced by a mocked
 * {@link JwtDecoder} (tests use {@code jwt()}); catalog by a mocked {@link CatalogClient}.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractOrderIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
                    .withDatabaseName("vab")
                    .withUsername("eventuate")
                    .withPassword("eventuate").withReuse(true)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("../deploy/postgres-init/01-eventuate-schema.sql"),
                            "/docker-entrypoint-initdb.d/01-eventuate-schema.sql")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("../deploy/postgres-init/04-tram-saga-schema.sql"),
                            "/docker-entrypoint-initdb.d/04-tram-saga-schema.sql");

    @SuppressWarnings("resource")
    static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7")).withReuse(true);

    @SuppressWarnings("resource")
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1")).withReuse(true);

    static {
        // Same as OrderServiceApplication.main: JavaTimeModule on Eventuate's JSonMapper.
        EventuateJackson.register();
        POSTGRES.start();
        MONGO.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("vab"));
        r.add("eventuatelocal.kafka.bootstrap.servers", KAFKA::getBootstrapServers);
    }

    @MockitoBean protected JwtDecoder jwtDecoder;
    @MockitoBean protected CatalogClient catalogClient;
}
