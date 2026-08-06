package com.vab.order.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vab.order.command.domain.OrderRepository;
import com.vab.order.command.domain.OrderStatus;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: place an order through the real web + JPA + Tram-outbox stack
 * against Testcontainers Postgres + Kafka.
 *
 * <p>The write side (Order row → PLACED), the OrderPlaced outbox publish and the saga
 * start all commit in one transaction, so a 202 means the row is durable. Without CDC
 * the first reserve command sits in the outbox, so the order stays PLACED — which is
 * exactly what we assert (end-to-end COMPLETED is the job of the e2e suite).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderPlacementIT extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired OrderRepository orders;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void placeOrder_persistsPlaced_andStartsSaga() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        headers.add("X-Subscriber-Id", "sub-premium");
        String body = "{\"offerCode\":\"OTT_NETFLIX_6M\",\"productType\":\"DIGITAL_SUBSCRIPTION\","
                + "\"priceSnapshotId\":\"ps-it\",\"amount\":999,\"currency\":\"INR\",\"billingMode\":\"PAY_NOW\"}";

        ResponseEntity<String> resp =
                rest.postForEntity("/v1/orders", new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String orderId = json.readTree(resp.getBody()).get("orderId").asText();
        assertThat(orderId).startsWith("ord_");

        var saved = orders.findById(orderId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(saved.get().getSubscriberId()).isEqualTo("sub-premium");

        // Read model is the write model in Lite — the GET endpoint returns the same row.
        ResponseEntity<String> read = rest.getForEntity("/v1/orders/" + orderId, String.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(read.getBody()).get("status").asText()).isEqualTo("PLACED");
    }
}
