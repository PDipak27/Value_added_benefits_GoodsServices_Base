package com.vab.notification.dispatch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRouterTest {

    private final NotificationRouter router = new NotificationRouter();

    @Test
    void confirmations_and_completions_go_to_push() {
        assertThat(router.routeFor(NotificationType.ORDER_CONFIRMED)).isEqualTo(Channel.PUSH);
        assertThat(router.routeFor(NotificationType.ORDER_COMPLETED)).isEqualTo(Channel.PUSH);
    }

    @Test
    void user_facing_failures_and_cancellations_go_to_sms() {
        assertThat(router.routeFor(NotificationType.ORDER_FAILED)).isEqualTo(Channel.SMS);
        assertThat(router.routeFor(NotificationType.ORDER_CANCELLED)).isEqualTo(Channel.SMS);
        assertThat(router.routeFor(NotificationType.ORDER_CANCELLED_REFUNDED)).isEqualTo(Channel.SMS);
    }
}
