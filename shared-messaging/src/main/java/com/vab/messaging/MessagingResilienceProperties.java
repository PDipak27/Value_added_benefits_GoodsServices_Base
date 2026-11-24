package com.vab.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code vab.messaging.resilience.*}: retry budgets of {@link ResilientMessageHandlerDecorator} (Design/13 §3, D7).
 * The defaults are the decided values; services normally don't override them.
 */
@ConfigurationProperties("vab.messaging.resilience")
public class MessagingResilienceProperties {

    /** Turns the decorator (and parking) off: Eventuate's default "one exception kills the subscription". */
    private boolean enabled = true;

    /** Total attempts for {@link FailureClass#CONTENTION} before the message is parked. */
    private int contentionMaxAttempts = 5;
    private Duration contentionInitialDelay = Duration.ofMillis(50);
    private Duration contentionMaxDelay = Duration.ofSeconds(1);

    /** Total attempts for {@link FailureClass#POISON} before the message is parked. */
    private int poisonMaxAttempts = 3;
    private Duration poisonDelay = Duration.ofMillis(200);

    /** Backoff while {@link FailureClass#INFRA_DOWN}: doubles from initial up to max, then stays there. Unbounded. */
    private Duration infraInitialDelay = Duration.ofSeconds(1);
    private Duration infraMaxDelay = Duration.ofSeconds(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getContentionMaxAttempts() { return contentionMaxAttempts; }
    public void setContentionMaxAttempts(int v) { this.contentionMaxAttempts = v; }
    public Duration getContentionInitialDelay() { return contentionInitialDelay; }
    public void setContentionInitialDelay(Duration v) { this.contentionInitialDelay = v; }
    public Duration getContentionMaxDelay() { return contentionMaxDelay; }
    public void setContentionMaxDelay(Duration v) { this.contentionMaxDelay = v; }
    public int getPoisonMaxAttempts() { return poisonMaxAttempts; }
    public void setPoisonMaxAttempts(int v) { this.poisonMaxAttempts = v; }
    public Duration getPoisonDelay() { return poisonDelay; }
    public void setPoisonDelay(Duration v) { this.poisonDelay = v; }
    public Duration getInfraInitialDelay() { return infraInitialDelay; }
    public void setInfraInitialDelay(Duration v) { this.infraInitialDelay = v; }
    public Duration getInfraMaxDelay() { return infraMaxDelay; }
    public void setInfraMaxDelay(Duration v) { this.infraMaxDelay = v; }
}
