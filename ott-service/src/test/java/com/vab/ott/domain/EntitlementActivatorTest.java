package com.vab.ott.domain;

import com.vab.ott.config.OttProperties;
import com.vab.ott.webhook.WebhookOutbox;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The activator decides each due PENDING entitlement and queues the matching webhook (Design/06 §8). */
class EntitlementActivatorTest {

    private final EntitlementRepository repo = mock(EntitlementRepository.class);
    private final WebhookOutbox outbox = mock(WebhookOutbox.class);
    private final OttProperties props = new OttProperties();
    private final EntitlementActivator activator = new EntitlementActivator(repo, outbox, props);

    private static Entitlement pending(String offerCode) {
        return new Entitlement("OTT-1", "ord-1", "sub-1", offerCode, null, null);
    }

    @Test
    void activates_due_entitlements_and_queues_activated() {
        Entitlement e = pending("OTT_NETFLIX_6M");
        when(repo.lockPendingDue(any(), anyInt())).thenReturn(List.of(e));

        assertThat(activator.activateDue()).isEqualTo(1);

        assertThat(e.getStatus()).isEqualTo(Entitlement.Status.ACTIVE);
        assertThat(e.getActivatedAt()).isNotNull();
        verify(outbox).enqueue(WebhookOutbox.ACTIVATED, e);
    }

    @Test
    void the_reject_trigger_rejects_and_queues_rejected() {
        Entitlement e = pending("OTT_OTTREJECT_1M");
        when(repo.lockPendingDue(any(), anyInt())).thenReturn(List.of(e));

        activator.activateDue();

        assertThat(e.getStatus()).isEqualTo(Entitlement.Status.REJECTED);
        assertThat(e.getRejectionReason()).isNotBlank();
        verify(outbox).enqueue(WebhookOutbox.REJECTED, e);
    }

    @Test
    void only_entitlements_older_than_the_activation_delay_are_due() {
        props.setActivationDelay(Duration.ofSeconds(30));
        when(repo.lockPendingDue(any(), anyInt())).thenReturn(List.of());

        activator.activateDue();

        verify(repo).lockPendingDue(org.mockito.ArgumentMatchers.argThat(
                cutoff -> Duration.between(cutoff, Instant.now()).toSeconds() >= 29), eq(20));
        verifyNoInteractions(outbox);
    }

    @Test
    void a_revoked_entitlement_is_never_activated() {
        Entitlement e = pending("OTT_NETFLIX_6M");
        e.revoke();

        assertThat(e.activate()).isFalse();
        assertThat(e.reject("x")).isFalse();
        assertThat(e.getStatus()).isEqualTo(Entitlement.Status.REVOKED);
        assertThat(e.getRevokedAt()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
    }
}
