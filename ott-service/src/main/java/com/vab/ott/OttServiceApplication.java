package com.vab.ott;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OTT Service — a standalone, third-party-style provider of streaming
 * entitlements (DD-27). It is intentionally outside the saga mesh: it speaks
 * plain REST, owns its own {@code ott} schema, and knows nothing about orders,
 * Tram or Kafka. {@code fulfilment-service} calls it to provision a
 * DIGITAL_SUBSCRIPTION; provisioning is idempotent on {@code orderId}.
 */
@SpringBootApplication
public class OttServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OttServiceApplication.class, args);
    }
}
