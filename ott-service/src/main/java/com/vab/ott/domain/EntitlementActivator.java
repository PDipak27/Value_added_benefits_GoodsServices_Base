package com.vab.ott.domain;

import com.vab.ott.config.OttProperties;
import com.vab.ott.webhook.WebhookOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Turns PENDING entitlements ACTIVE a moment after the partner asked for them, the way a real platform
 * finishes account setup in the background (Design/06 §8). Each decision and its webhook commit together.
 * Demo trigger: an offer code containing {@value #REJECT_TRIGGER} is rejected here instead.
 */
@Component
public class EntitlementActivator {

    static final String REJECT_TRIGGER = "OTTREJECT";

    private static final Logger log = LoggerFactory.getLogger(EntitlementActivator.class);
    private static final int BATCH = 20;

    private final EntitlementRepository entitlements;
    private final WebhookOutbox outbox;
    private final OttProperties props;

    public EntitlementActivator(EntitlementRepository entitlements, WebhookOutbox outbox, OttProperties props) {
        this.entitlements = entitlements;
        this.outbox       = outbox;
        this.props        = props;
    }

    @Scheduled(fixedDelayString = "${ott.activation-poll-interval:1s}")
    @Transactional
    public int activateDue() {
        var due = entitlements.lockPendingDue(Instant.now().minus(props.getActivationDelay()), BATCH);
        for (Entitlement e : due) {
            if (e.getOfferCode().toUpperCase().contains(REJECT_TRIGGER)) {
                e.reject("Plan not available for this account");
                outbox.enqueue(WebhookOutbox.REJECTED, e);
                log.warn("Entitlement REJECTED (demo trigger): externalRef={}, orderId={}", e.getExternalRef(), e.getOrderId());
            } else {
                e.activate();
                outbox.enqueue(WebhookOutbox.ACTIVATED, e);
                log.info("Entitlement ACTIVATED: externalRef={}, orderId={}", e.getExternalRef(), e.getOrderId());
            }
        }
        return due.size();
    }
}
