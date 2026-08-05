package com.vab.order.it;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Shared Testcontainers infra for the Lite integration tests: a real Postgres 18
 * and a real Kafka. Postgres is pre-seeded with the eventuate ES/Tram + saga schema
 * via the SAME init SQL the compose uses — the services' own Flyway only manages the
 * orders/inventory/billing schemas, not the eventuate one. Containers are static
 * singletons: started once, reused across every {@code *IT} in the module.
 *
 * <p>No eventuate-cdc here, so nothing relays the outbox → cross-service saga steps
 * don't advance. These ITs assert single-service behaviour (web + JPA + Flyway +
 * outbox write), not end-to-end completion (that's the e2e suite).
 */
@Testcontainers
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
                    .withDatabaseName("vab")
                    .withUsername("eventuate")
                    .withPassword("eventuate")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("../deploy/postgres-init/01-eventuate-schema.sql"),
                            "/docker-entrypoint-initdb.d/01-eventuate-schema.sql")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("../deploy/postgres-init/04-tram-saga-schema.sql"),
                            "/docker-entrypoint-initdb.d/04-tram-saga-schema.sql");

    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("eventuatelocal.kafka.bootstrap.servers", KAFKA::getBootstrapServers);
    }
}
