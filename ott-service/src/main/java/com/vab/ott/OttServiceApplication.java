package com.vab.ott;

import org.springframework.boot.SpringApplication;
import com.vab.ott.config.OttProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OTT Service — a standalone, third-party-style provider of streaming
 * entitlements (DD-27). It is intentionally outside the saga mesh: it speaks
 * plain REST, owns its own {@code ott} schema, and knows nothing about orders,
 * Tram or Kafka. {@code fulfilment-service} calls it with an API key to provision a
 * DIGITAL_SUBSCRIPTION (idempotent on {@code orderId}); activation happens asynchronously and is
 * reported back with signed webhooks (Design/06 §8).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(OttProperties.class)
public class OttServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OttServiceApplication.class, args);
    }
}
