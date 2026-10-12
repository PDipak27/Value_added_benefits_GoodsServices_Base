package com.vab.fulfilment.it;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Shared Testcontainers infra for fulfilment ITs: Postgres 18 pre-seeded with the eventuate Tram +
 * saga schema (as compose does) and Kafka (the participant's command consumer must subscribe).
 * Static singletons, reused across every {@code *IT} in the module.
 */
public abstract class AbstractIntegrationTest {

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
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1")).withReuse(true);

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
