package com.vab.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Catalog &amp; Eligibility Service.
 *
 * <p>Owns offer definitions, price snapshots, and eligibility rules. Exposes a
 * read-mostly REST surface; eligibility filtering is applied server-side.
 *
 * <p>Event emission (OfferPublished / OfferWithdrawn / PriceChanged) is deferred
 * until the messaging dependencies are added — see Design/02.
 */
@SpringBootApplication
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
