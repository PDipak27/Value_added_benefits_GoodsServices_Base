package com.vab.inventory.it;

import com.vab.inventory.domain.InventoryItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: the inventory participant boots against real Postgres + Kafka and
 * its Flyway migrations (schema + seed inventory) apply cleanly. Proves the module's
 * persistence + Tram wiring stand up on a real database, not H2.
 */
@SpringBootTest
class InventorySeedIT extends AbstractIntegrationTest {

    @Autowired InventoryItemRepository items;

    @Test
    @Transactional   // findByOfferCodeForUpdate takes a PESSIMISTIC_WRITE lock → needs a tx
    void flywaySeedApplied_onRealPostgres() {
        // V2/V4 seed the offer catalogue (incl. OTT_NETFLIX_6M used by the e2e).
        assertThat(items.count()).isGreaterThan(0);
        assertThat(items.findByOfferCodeForUpdate("OTT_NETFLIX_6M")).isPresent();
    }
}
