package com.vab.paymentsim;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code vab.psp.*}: the one merchant account (billing) this simulator serves. */
@ConfigurationProperties("vab.psp")
public class PspProperties {

    /** Merchant API credentials, sent by billing as HTTP Basic {@code keyId:keySecret}. */
    private String keyId = "vab_test_key";
    private String keySecret = "vab_test_secret";

    /** Shared secret both sides use for the webhook HMAC; never sent on the wire. */
    private String webhookSecret = "whsec_vab_test";

    /** Where webhooks go (billing's endpoint). */
    private String webhookUrl = "http://localhost:8083/webhooks/psp";

    /** Base of the checkout URL the subscriber's browser opens. */
    private String publicBaseUrl = "http://localhost:8091";

    /** How long an unpaid intent can still be paid. */
    private Duration intentTtl = Duration.ofMinutes(8);

    /** Webhook delivery: retries with doubling backoff up to this cap, then gives up. */
    private int webhookMaxAttempts = 15;
    private Duration webhookMaxBackoff = Duration.ofMinutes(1);

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getKeySecret() { return keySecret; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public Duration getIntentTtl() { return intentTtl; }
    public void setIntentTtl(Duration intentTtl) { this.intentTtl = intentTtl; }
    public int getWebhookMaxAttempts() { return webhookMaxAttempts; }
    public void setWebhookMaxAttempts(int webhookMaxAttempts) { this.webhookMaxAttempts = webhookMaxAttempts; }
    public Duration getWebhookMaxBackoff() { return webhookMaxBackoff; }
    public void setWebhookMaxBackoff(Duration webhookMaxBackoff) { this.webhookMaxBackoff = webhookMaxBackoff; }
}
