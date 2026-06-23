package com.vab.order.command.domain;

import com.vab.order.idempotency.IdempotencyKey;
import com.vab.order.idempotency.IdempotencyKeyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Write-side persistence against a real Postgres (Testcontainers): Flyway builds
 * the orders schema, then the Order aggregate and idempotency key round-trip —
 * including the DD-27 FULFILMENT_FAILED park that triggers the admin alert.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OrderPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OrderRepository orderRepo;
    @Autowired IdempotencyKeyRepository idempotencyRepo;

    @Test
    void placed_order_round_trips() {
        orderRepo.saveAndFlush(Order.place("ord_it1", "sub-1", "OFF-1",
                "DIGITAL_SUBSCRIPTION", "px-1", 500, "INR", "PAY_NOW"));

        Order reloaded = orderRepo.findById("ord_it1").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(reloaded.getProductType()).isEqualTo("DIGITAL_SUBSCRIPTION");
    }

    @Test
    void parking_an_order_persists_fulfilment_failed_state() {
        Order order = Order.place("ord_it2", "sub-1", "OFF-1",
                "DIGITAL_SUBSCRIPTION", "px-1", 500, "INR", "PAY_NOW");
        orderRepo.saveAndFlush(order);

        order.fulfilmentFailed("PROVISIONING_UNAVAILABLE: 503");  // DD-27 park
        orderRepo.saveAndFlush(order);

        Order parked = orderRepo.findById("ord_it2").orElseThrow();
        assertThat(parked.getStatus()).isEqualTo(OrderStatus.FULFILMENT_FAILED);
        assertThat(parked.getFailedStep()).isEqualTo("FULFIL_PROVISION");
        assertThat(parked.getLastAttemptAt()).isNotNull();
    }

    @Test
    void idempotency_key_unique_lookup() {
        idempotencyRepo.save(new IdempotencyKey("sub-1", "idem-1", "ord_it1"));

        Optional<IdempotencyKey> found =
                idempotencyRepo.findBySubscriberIdAndIdempotencyKey("sub-1", "idem-1");
        assertThat(found).isPresent();
        assertThat(found.get().getOrderId()).isEqualTo("ord_it1");
    }
}
