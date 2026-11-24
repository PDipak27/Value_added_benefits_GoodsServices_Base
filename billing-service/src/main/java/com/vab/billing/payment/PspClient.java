package com.vab.billing.payment;

import com.vab.observability.Correlation;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * The payment provider's merchant API (Design/15), one attempt per call behind the {@code psp} circuit
 * breaker. Never throws: every outcome is a result. Mutations carry an {@code Idempotency-Key}, and the
 * provider treats a repeat as a no-op, so a retry after a timeout can't charge or refund twice.
 */
@Component
public class PspClient {

    public enum Outcome { OK, REJECTED, UNAVAILABLE }

    public record IntentResult(Outcome outcome, String intentId, String checkoutUrl, Instant expiresAt, String detail) {}

    public record CallResult(Outcome outcome, String detail) {
        public boolean ok() { return outcome == Outcome.OK; }
    }

    record CreateIntentRequest(String orderId, long amount, String currency) {}

    record IntentResponse(String id, String orderId, String status, long amount, String currency,
                          String checkoutUrl, Instant expiresAt, String failureReason) {}

    private static final Logger log = LoggerFactory.getLogger(PspClient.class);

    private final RestClient http;
    private final CircuitBreaker breaker;

    public PspClient(RestClient.Builder builder, CircuitBreakerRegistry breakers, PaymentProperties props) {
        this.breaker = breakers.circuitBreaker("psp");
        this.http = builder.baseUrl(props.getPspBaseUrl())
                .defaultHeaders(h -> h.setBasicAuth(props.getKeyId(), props.getKeySecret()))
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(Correlation.MDC_CORRELATION_ID);
                    if (correlationId != null) request.getHeaders().set(Correlation.HEADER, correlationId);
                    return execution.execute(request, body);
                })
                .build();
    }

    public IntentResult createIntent(String orderId, long amount, String currency) {
        try {
            IntentResponse r = breaker.executeSupplier(() -> http.post().uri("/v1/payment_intents")
                    .header("Idempotency-Key", "create-" + orderId)
                    .body(new CreateIntentRequest(orderId, amount, currency))
                    .retrieve()
                    .body(IntentResponse.class));
            if (r == null || r.id() == null) {
                return new IntentResult(Outcome.UNAVAILABLE, null, null, null, "empty response");
            }
            log.info("PSP intent created: orderId={}, intentId={}, expiresAt={}", orderId, r.id(), r.expiresAt());
            return new IntentResult(Outcome.OK, r.id(), r.checkoutUrl(), r.expiresAt(), null);
        } catch (HttpClientErrorException e) {
            log.warn("PSP create REJECTED: orderId={}, {}", orderId, e.getStatusCode());
            return new IntentResult(Outcome.REJECTED, null, null, null, "HTTP " + e.getStatusCode().value());
        } catch (RuntimeException e) {
            log.warn("PSP create UNAVAILABLE: orderId={}, {}", orderId, e.toString());
            return new IntentResult(Outcome.UNAVAILABLE, null, null, null, shortDetail(e));
        }
    }

    public CallResult capture(String intentId) {
        return call("capture", intentId);
    }

    /** Cancels an unpaid intent, or voids an authorisation. */
    public CallResult cancel(String intentId) {
        return call("cancel", intentId);
    }

    public CallResult refund(String intentId) {
        return call("refund", intentId);
    }

    private CallResult call(String action, String intentId) {
        try {
            breaker.executeRunnable(() -> http.post().uri("/v1/payment_intents/{id}/" + action, intentId)
                    .header("Idempotency-Key", action + "-" + intentId)
                    .retrieve()
                    .toBodilessEntity());
            log.info("PSP {} OK: intentId={}", action, intentId);
            return new CallResult(Outcome.OK, null);
        } catch (HttpClientErrorException e) {
            log.warn("PSP {} REJECTED: intentId={}, {} {}", action, intentId, e.getStatusCode(), e.getResponseBodyAsString());
            return new CallResult(Outcome.REJECTED, "HTTP " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            log.warn("PSP {} UNAVAILABLE: intentId={}, {}", action, intentId, e.toString());
            return new CallResult(Outcome.UNAVAILABLE, shortDetail(e));
        }
    }

    private static String shortDetail(Throwable e) {
        String s = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
