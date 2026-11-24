package com.vab.ott.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code ott.*}: the one partner (VA-BAGS) this OTT platform serves (Design/06 §8). */
@ConfigurationProperties("ott")
public class OttProperties {

    /** Partner API key, sent as {@code X-Api-Key} on every entitlement API call. */
    private String apiKey = "ott_test_key_vab";

    /** Where the partner wants webhooks, and the shared secret they're signed with. */
    private String webhookUrl = "http://localhost:8086/webhooks/ott";
    private String webhookSecret = "whsec_ott_test";

    /** How long a new entitlement stays PENDING before the activator decides. */
    private Duration activationDelay = Duration.ofSeconds(2);

    /** Webhook delivery: retries with doubling backoff up to this cap, then the event is marked FAILED. */
    private int webhookMaxAttempts = 20;
    private Duration webhookMaxBackoff = Duration.ofMinutes(1);

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public Duration getActivationDelay() { return activationDelay; }
    public void setActivationDelay(Duration activationDelay) { this.activationDelay = activationDelay; }
    public int getWebhookMaxAttempts() { return webhookMaxAttempts; }
    public void setWebhookMaxAttempts(int webhookMaxAttempts) { this.webhookMaxAttempts = webhookMaxAttempts; }
    public Duration getWebhookMaxBackoff() { return webhookMaxBackoff; }
    public void setWebhookMaxBackoff(Duration webhookMaxBackoff) { this.webhookMaxBackoff = webhookMaxBackoff; }
}
