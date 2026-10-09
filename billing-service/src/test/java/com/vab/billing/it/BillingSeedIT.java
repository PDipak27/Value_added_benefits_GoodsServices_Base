package com.vab.billing.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.vab.billing.domain.BillingAccountRepository;

/**
 * Integration test: the billing participant boots against real Postgres + Kafka and
 * its Flyway seed (billing accounts) applies. Confirms the seeded {@code sub-premium}
 * account — the identity the PAY_NOW e2e / IT charges — is ACTIVE with its credit limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillingSeedIT extends AbstractIntegrationTest {

    @Autowired BillingAccountRepository accounts;

	/* Ryuk  closes testContainer, when process ends
	 * @AfterAll static void clear() { POSTGRES.close(); KAFKA.close(); }
	 */
    @Test
    void seededPremiumAccount_isActive_onRealPostgres() {
        var premium = accounts.findById("sub-premium");
        System.out.println("premium "+premium.get().getSubscriberId() );
        assertThat(premium).isPresent();
        assertThat(premium.get().isActive()).isTrue();
        assertThat(premium.get().getCreditLimit()).isEqualTo(5000L);
    }
}
