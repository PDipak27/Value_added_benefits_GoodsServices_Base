package com.vab.notification.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Simulated delivery gateway. A real implementation would call an SMS/email/push
 * provider; the stub logs the send and returns a provider reference.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    /** @return provider reference for the (simulated) accepted message. */
    public String dispatch(Channel channel, String recipient, String body) {
        String providerRef = "msg_" + UUID.randomUUID();
        log.info("Dispatch via {} to {} [{}]: {}", channel, recipient, providerRef, body);
        return providerRef;
    }
}
