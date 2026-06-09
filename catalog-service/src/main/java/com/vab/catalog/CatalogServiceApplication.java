package com.vab.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Catalog &amp; Eligibility Service.
 *
 * <p>Owns offer definitions, price snapshots, and eligibility rules. Exposes a
 * read-mostly REST surface (eligibility filtering applied server-side) plus an
 * admin write API. Reads are served through a Redis cache invalidated by
 * evict-on-write + a short TTL (DD-17).
 *
 * <p>Catalog domain-event emission (OfferPublished / OfferWithdrawn /
 * PriceChanged) for cross-service consumers is deferred — it is a separate
 * concern from cache invalidation; see Design/02 and DD-17.
 */
@SpringBootApplication
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
