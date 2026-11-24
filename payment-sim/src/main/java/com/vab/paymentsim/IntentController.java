package com.vab.paymentsim;

import com.vab.paymentsim.PaymentIntent.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Merchant API (API key, see {@link ApiKeyFilter}). Every mutation is idempotent: repeating it on an intent
 * already in the target state answers 200 with no change.
 *
 * <p>Demo triggers: amount {@value #PROVIDER_DOWN_AMOUNT} → create answers 503 (provider outage);
 * amount {@value #CAPTURE_DECLINE_AMOUNT} → capture answers 402 (the bank refused settlement).
 */
@RestController
@RequestMapping("/v1/payment_intents")
public class IntentController {

    static final long PROVIDER_DOWN_AMOUNT   = 503;
    static final long CAPTURE_DECLINE_AMOUNT = 777;

    private static final Logger log = LoggerFactory.getLogger(IntentController.class);

    private final IntentStore store;
    private final PspProperties props;

    public IntentController(IntentStore store, PspProperties props) {
        this.store = store;
        this.props = props;
    }

    public record CreateIntentRequest(String orderId, long amount, String currency) {}

    public record IntentResponse(String id, String orderId, String status, long amount, String currency,
                                 String checkoutUrl, Instant expiresAt, String failureReason) {}

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @RequestBody CreateIntentRequest req) {
        if (req.amount() <= 0 || req.currency() == null || req.orderId() == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        if (req.amount() == PROVIDER_DOWN_AMOUNT) {
            log.warn("Demo outage: create refused for orderId={}", req.orderId());
            return error(HttpStatus.SERVICE_UNAVAILABLE, "provider_unavailable");
        }
        PaymentIntent intent = store.create(idempotencyKey, req.orderId(), req.amount(), req.currency());
        log.info("Intent created: id={}, orderId={}, amount={} {}", intent.getId(), req.orderId(),
                req.amount(), req.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(intent));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return store.find(id).<ResponseEntity<?>>map(i -> ResponseEntity.ok(view(i)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "no_such_intent"));
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<?> capture(@PathVariable String id) {
        return transition(id, Set.of(Status.AUTHORIZED), Status.CAPTURED, intent -> {
            if (intent.getAmount() == CAPTURE_DECLINE_AMOUNT) {
                return error(HttpStatus.PAYMENT_REQUIRED, "card_declined");
            }
            return null;
        });
    }

    /** Cancels an unpaid intent, or voids an authorisation (the hold on the customer's card is released). */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        PaymentIntent intent = store.find(id).orElse(null);
        if (intent == null) return error(HttpStatus.NOT_FOUND, "no_such_intent");
        synchronized (intent) {
            switch (intent.getStatus()) {
                case REQUIRES_PAYMENT -> intent.setStatus(Status.CANCELED);
                case AUTHORIZED       -> intent.setStatus(Status.VOIDED);
                case CANCELED, VOIDED -> { }
                default -> { return error(HttpStatus.CONFLICT, "intent_" + intent.getStatus().name().toLowerCase()); }
            }
            log.info("Intent cancelled: id={}, status={}", id, intent.getStatus());
            return ResponseEntity.ok(view(intent));
        }
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<?> refund(@PathVariable String id) {
        return transition(id, Set.of(Status.CAPTURED), Status.REFUNDED, intent -> null);
    }

    private ResponseEntity<?> transition(String id, Set<Status> from, Status to,
                                         java.util.function.Function<PaymentIntent, ResponseEntity<?>> veto) {
        PaymentIntent intent = store.find(id).orElse(null);
        if (intent == null) return error(HttpStatus.NOT_FOUND, "no_such_intent");
        synchronized (intent) {
            if (intent.getStatus() == to) return ResponseEntity.ok(view(intent));
            if (!from.contains(intent.getStatus())) {
                return error(HttpStatus.CONFLICT, "intent_" + intent.getStatus().name().toLowerCase());
            }
            ResponseEntity<?> refused = veto.apply(intent);
            if (refused != null) return refused;
            intent.setStatus(to);
            log.info("Intent {}: id={}, orderId={}", to, id, intent.getOrderId());
            return ResponseEntity.ok(view(intent));
        }
    }

    IntentResponse view(PaymentIntent i) {
        String checkoutUrl = props.getPublicBaseUrl() + "/checkout/" + i.getId() + "?cs=" + i.getClientSecret();
        String status = i.isExpired(Instant.now()) ? "EXPIRED" : i.getStatus().name();
        return new IntentResponse(i.getId(), i.getOrderId(), status, i.getAmount(), i.getCurrency(),
                checkoutUrl, i.getExpiresAt(), i.getFailureReason());
    }

    private static ResponseEntity<?> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
