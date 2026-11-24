package com.vab.paymentsim;

import com.vab.paymentsim.CheckoutPages.Outcome;
import com.vab.paymentsim.PaymentIntent.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Hosted checkout: the only step where the subscriber is live. No login; the intent's single-use client
 * secret in the URL is the capability. Approve needs the simulated 3-D Secure OTP {@value #OTP}.
 * {@code POST /checkout/{id}/decision} also takes plain form/query parameters, so E2E tests can pay
 * without a browser.
 */
@RestController
public class CheckoutController {

    static final String OTP = "123456";

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final IntentStore store;
    private final WebhookDispatcher webhooks;

    public CheckoutController(IntentStore store, WebhookDispatcher webhooks) {
        this.store    = store;
        this.webhooks = webhooks;
    }

    @GetMapping(value = "/checkout/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String id, @RequestParam("cs") String clientSecret) {
        PaymentIntent intent = authorised(id, clientSecret);
        if (intent == null) return notFound();
        synchronized (intent) {
            if (intent.isExpired(Instant.now())) return expired();
            if (intent.getStatus() != Status.REQUIRES_PAYMENT) {
                return html(HttpStatus.OK, CheckoutPages.result(Outcome.WARNING, "Already processed",
                        "This payment has already been processed.", intent));
            }
            return html(HttpStatus.OK, CheckoutPages.form(intent, clientSecret, null));
        }
    }

    @PostMapping(value = "/checkout/{id}/decision", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> decide(@PathVariable String id,
                                         @RequestParam("cs") String clientSecret,
                                         @RequestParam("decision") String decision,
                                         @RequestParam(value = "otp", required = false) String otp) {
        PaymentIntent intent = authorised(id, clientSecret);
        if (intent == null) return notFound();
        synchronized (intent) {
            if (intent.isExpired(Instant.now())) return expired();
            if (intent.getStatus() != Status.REQUIRES_PAYMENT) {
                return html(HttpStatus.CONFLICT, CheckoutPages.result(Outcome.WARNING, "Already processed",
                        "This payment has already been processed.", intent));
            }
            if ("approve".equals(decision)) {
                if (!OTP.equals(otp)) {
                    // Back to the form with the error: the customer just retries.
                    return html(HttpStatus.BAD_REQUEST, CheckoutPages.form(intent, clientSecret,
                            "That OTP didn't match. Check the code and try again."));
                }
                intent.setStatus(Status.AUTHORIZED);
                webhooks.publish("payment.authorized", intent);
                log.info("Checkout approved: intentId={}, orderId={}", id, intent.getOrderId());
                return html(HttpStatus.OK, CheckoutPages.result(Outcome.SUCCESS, "Payment authorised",
                        "Your order is being confirmed. You can return to VA-BAGS.", intent));
            }
            intent.setStatus(Status.FAILED);
            intent.setFailureReason("customer_declined");
            webhooks.publish("payment.failed", intent);
            log.info("Checkout declined: intentId={}, orderId={}", id, intent.getOrderId());
            return html(HttpStatus.OK, CheckoutPages.result(Outcome.FAILURE, "Payment declined",
                    "No money was taken.", intent));
        }
    }

    private static ResponseEntity<String> notFound() {
        return html(HttpStatus.NOT_FOUND, CheckoutPages.result(Outcome.WARNING, "Payment not found",
                "This payment link is not valid.", null));
    }

    private static ResponseEntity<String> expired() {
        return html(HttpStatus.GONE, CheckoutPages.result(Outcome.WARNING, "Payment expired",
                "This payment window has closed. Nothing was charged.", null));
    }

    private PaymentIntent authorised(String id, String clientSecret) {
        PaymentIntent intent = store.find(id).orElse(null);
        if (intent == null || clientSecret == null) return null;
        boolean ok = MessageDigest.isEqual(intent.getClientSecret().getBytes(StandardCharsets.UTF_8),
                clientSecret.getBytes(StandardCharsets.UTF_8));
        return ok ? intent : null;
    }

    private static ResponseEntity<String> html(HttpStatus status, String page) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(page);
    }
}
