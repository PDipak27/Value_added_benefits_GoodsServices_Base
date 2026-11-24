package com.vab.billing.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code vab.billing.payments.*} (Design/15): the payment provider account and the sweeper's pacing. */
@ConfigurationProperties("vab.billing.payments")
public class PaymentProperties {

    /** Provider API base URL and this merchant's API key (HTTP Basic). */
    private String pspBaseUrl = "http://localhost:8091";
    private String keyId = "vab_test_key";
    private String keySecret = "vab_test_secret";

    /** Shared secret for the webhook HMAC, and how old a signed timestamp may be (replay window). */
    private String webhookSecret = "whsec_vab_test";
    private Duration signatureTolerance = Duration.ofMinutes(5);

    /** Sweeper: how often it runs, how many rows per pass, and the retry interval for an owed void/refund. */
    private Duration sweepInterval = Duration.ofSeconds(5);
    private int batchSize = 10;
    private Duration retryInterval = Duration.ofMinutes(1);

    public String getPspBaseUrl() { return pspBaseUrl; }
    public void setPspBaseUrl(String pspBaseUrl) { this.pspBaseUrl = pspBaseUrl; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getKeySecret() { return keySecret; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public Duration getSignatureTolerance() { return signatureTolerance; }
    public void setSignatureTolerance(Duration signatureTolerance) { this.signatureTolerance = signatureTolerance; }
    public Duration getSweepInterval() { return sweepInterval; }
    public void setSweepInterval(Duration sweepInterval) { this.sweepInterval = sweepInterval; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getRetryInterval() { return retryInterval; }
    public void setRetryInterval(Duration retryInterval) { this.retryInterval = retryInterval; }
}
