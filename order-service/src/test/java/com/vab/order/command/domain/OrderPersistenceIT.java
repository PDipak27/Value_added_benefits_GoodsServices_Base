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

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Write-side persistence against a real Postgres (Testcontainers): Flyway builds
 * the orders schema, then the Order aggregate and idempotency key round-trip.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OrderPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

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
    void cancel_flag_persists_through_the_locking_read() {
        orderRepo.saveAndFlush(Order.place("ord_it2", "sub-1", "OFF-1",
                "DIGITAL_SUBSCRIPTION", "px-1", 500, "INR", "PAY_NOW"));

        Order locked = orderRepo.findByIdForUpdate("ord_it2").orElseThrow();
        locked.requestCancel();
        orderRepo.saveAndFlush(locked);

        assertThat(orderRepo.findById("ord_it2").orElseThrow().isCancelRequested()).isTrue();
    }

    @Test
    void pending_payment_link_persists() {
        Order order = Order.place("ord_it3", "sub-1", "OFF-1", "PHYSICAL_GOOD", "px-1", 500, "INR", "PAY_NOW");
        orderRepo.saveAndFlush(order);
        Instant expires = Instant.parse("2026-11-16T10:08:00Z");
        order.awaitPayment("http://psp/checkout/pi_1?cs=x", expires);
        orderRepo.saveAndFlush(order);

        Order reloaded = orderRepo.findById("ord_it3").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(reloaded.getCheckoutUrl()).isEqualTo("http://psp/checkout/pi_1?cs=x");
        assertThat(reloaded.getPaymentExpiresAt()).isEqualTo(expires);
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
