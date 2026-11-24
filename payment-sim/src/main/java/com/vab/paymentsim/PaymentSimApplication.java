package com.vab.paymentsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payment provider simulator (Design/15). Plays the external provider billing integrates with: merchant API
 * (API key), hosted checkout page (the subscriber is live here), signed webhooks with retries. State is
 * in memory: a restart forgets intents, as if the provider had lost them, and billing expires them.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PspProperties.class)
public class PaymentSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentSimApplication.class, args);
    }
}
