package com.vab.billing.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes unpaid payment windows (the saga gets {@code PaymentNotCompleted(EXPIRED)}) and retries owed voids
 * and refunds. Safe with several replicas: rows are claimed with {@code FOR UPDATE SKIP LOCKED}.
 */
@Component
public class PaymentSweeper {

    private static final Logger log = LoggerFactory.getLogger(PaymentSweeper.class);

    private final PaymentService payments;

    public PaymentSweeper(PaymentService payments) {
        this.payments = payments;
    }

    @Scheduled(fixedDelayString = "${vab.billing.payments.sweep-interval:5s}",
               initialDelayString = "${vab.billing.payments.sweep-interval:5s}")
    public void sweep() {
        try {
            payments.expireDue();
        } catch (RuntimeException e) {
            log.warn("Payment sweeper: expiring intents failed: {}", e.toString());
        }
        try {
            payments.retryOwedDue();
        } catch (RuntimeException e) {
            log.warn("Payment sweeper: owed voids/refunds failed: {}", e.toString());
        }
    }
}
