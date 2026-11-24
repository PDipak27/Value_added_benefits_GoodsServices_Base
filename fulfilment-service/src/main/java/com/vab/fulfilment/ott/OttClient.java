package com.vab.fulfilment.ott;

import com.vab.observability.Correlation;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Instant;

/**
 * REST client over the external {@code ott-service} (DD-27, revised by Design/13 §6).
 *
 * <p>Authenticated with the partner API key OTT issued us ({@code X-Api-Key}, Design/06 §8). One attempt per
 * call, behind the {@code ott} circuit breaker, with the connect/read timeouts of the auto-configured
 * {@link RestClient.Builder} (2 s / 5 s). Retrying is the job of fulfilment's provisioning task, not of this
 * client. Outcomes:
 * <ul>
 *   <li>{@code ACCEPTED}: 202 PENDING. OTT activates shortly and tells us by webhook.</li>
 *   <li>{@code PROVISIONED}: a repeat request found the entitlement already ACTIVE (OTT is idempotent on
 *       {@code orderId}); the response carries its validity.</li>
 *   <li>{@code REJECTED}: 422, or a repeat request found it REJECTED. Permanent.</li>
 *   <li>{@code UNAVAILABLE}: anything else (5xx, timeout, refused, breaker open).
 *       {@link Result#uncertain()} says whether OTT might still have created the entitlement. It is false
 *       only when the request certainly never reached OTT.</li>
 * </ul>
 */
@Component
public class OttClient {

    private static final Logger log = LoggerFactory.getLogger(OttClient.class);

    static final String API_KEY_HEADER = "X-Api-Key";

    public enum Outcome { ACCEPTED, PROVISIONED, REJECTED, UNAVAILABLE }

    /** Outcome of one provisioning call; {@code validFrom/validUntil} only when OTT reported them. */
    public record Result(Outcome outcome, String externalRef, String reason, String detail, boolean uncertain,
                         Instant validFrom, Instant validUntil) {

        public Result(Outcome outcome, String externalRef, String reason, String detail, boolean uncertain) {
            this(outcome, externalRef, reason, detail, uncertain, null, null);
        }

        public boolean provisioned() { return outcome == Outcome.PROVISIONED; }

        static Result accepted(String externalRef) {
            return new Result(Outcome.ACCEPTED, externalRef, null, null, true);
        }
        static Result ok(String externalRef, Instant validFrom, Instant validUntil) {
            return new Result(Outcome.PROVISIONED, externalRef, null, null, true, validFrom, validUntil);
        }
        static Result rejected(String detail) {
            return new Result(Outcome.REJECTED, null, "PROVISIONING_REJECTED", detail, false);
        }
        static Result unavailable(String detail, boolean uncertain) {
            return new Result(Outcome.UNAVAILABLE, null, "PROVISIONING_UNAVAILABLE", detail, uncertain);
        }
    }

    private final RestClient restClient;
    private final CircuitBreaker breaker;

    public OttClient(RestClient.Builder builder,
                     CircuitBreakerRegistry breakers,
                     @Value("${ott.base-url:http://localhost:8087}") String baseUrl,
                     @Value("${ott.api-key:ott_test_key_vab}") String apiKey) {
        this.breaker    = breakers.circuitBreaker("ott");
        // Design/06 §8: every call to the partner API carries the API key OTT issued to VA-BAGS.
        this.restClient = builder.baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set(API_KEY_HEADER, apiKey);
                    // §C2 B-2: forward the correlation id so ott-service's logs join this order's trail.
                    String correlationId = MDC.get(Correlation.MDC_CORRELATION_ID);
                    if (correlationId != null) request.getHeaders().set(Correlation.HEADER, correlationId);
                    return execution.execute(request, body);
                })
                .build();
    }

    /** True unless the breaker is open: callers skip OTT work while it's known to be down. */
    public boolean isCallPermitted() {
        CircuitBreaker.State s = breaker.getState();
        return s != CircuitBreaker.State.OPEN && s != CircuitBreaker.State.FORCED_OPEN;
    }

    public Result provision(String orderId, String subscriberId, String offerCode,
                            Instant validFrom, Instant validUntil) {
        try {
            EntitlementResponse resp = breaker.executeSupplier(() -> restClient.post()
                    .uri("/ott/v1/entitlements")
                    .body(new ProvisionRequest(orderId, subscriberId, offerCode, validFrom, validUntil))
                    .retrieve()
                    .body(EntitlementResponse.class));
            if (resp == null || resp.externalRef() == null) {
                return Result.unavailable("empty response", true);
            }
            switch (resp.status() == null ? "PENDING" : resp.status()) {
                case "ACTIVE" -> {
                    log.info("OTT provision: already ACTIVE, orderId={}, externalRef={}", orderId, resp.externalRef());
                    return Result.ok(resp.externalRef(), resp.validFrom(), resp.validUntil());
                }
                case "REJECTED", "REVOKED" -> {
                    String detail = "OTT reports " + resp.status() + (resp.reason() == null ? "" : ": " + resp.reason());
                    log.warn("OTT provision: {} for orderId={}", detail, orderId);
                    return Result.rejected(detail);
                }
                default -> {
                    log.info("OTT provision ACCEPTED (pending activation): orderId={}, externalRef={}", orderId, resp.externalRef());
                    return Result.accepted(resp.externalRef());
                }
            }
        } catch (HttpClientErrorException.UnprocessableEntity e) {
            String detail = shortDetail(e);
            log.warn("OTT provision REJECTED (422): orderId={}, {}", orderId, detail);
            return Result.rejected(detail);
        } catch (RuntimeException e) {
            boolean uncertain = !certainlyNotSent(e);
            String detail = shortDetail(e);
            log.warn("OTT provision UNAVAILABLE: orderId={}, uncertain={}, {}", orderId, uncertain, detail);
            return Result.unavailable(detail, uncertain);
        }
    }

    /** Admin revoke (Phase 3): DELETE the OTT entitlement by its ref. Idempotent at OTT; false on any error. */
    public boolean revoke(String externalRef) {
        try {
            breaker.executeRunnable(() -> restClient.delete()
                    .uri("/ott/v1/entitlements/{ref}", externalRef)
                    .retrieve()
                    .toBodilessEntity());
            log.info("OTT revoke OK: externalRef={}", externalRef);
            return true;
        } catch (RuntimeException e) {
            log.warn("OTT revoke failed: externalRef={}, {}", externalRef, shortDetail(e));
            return false;
        }
    }

    /**
     * Revoke whatever entitlement exists for an order (Design/13 §6). Used when a refund must not leave
     * the subscriber with the subscription, including after a timed-out provision whose ref we never got.
     * Idempotent at OTT (204 even if none exists); false on any error.
     */
    public boolean revokeByOrder(String orderId) {
        try {
            breaker.executeRunnable(() -> restClient.delete()
                    .uri("/ott/v1/entitlements/by-order/{orderId}", orderId)
                    .retrieve()
                    .toBodilessEntity());
            log.info("OTT revoke-by-order OK: orderId={}", orderId);
            return true;
        } catch (RuntimeException e) {
            log.warn("OTT revoke-by-order failed: orderId={}, {}", orderId, shortDetail(e));
            return false;
        }
    }

    /** True only if the request provably never reached OTT (so no entitlement can exist). */
    private static boolean certainlyNotSent(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof CallNotPermittedException
                    || c instanceof ConnectException
                    || c instanceof UnknownHostException
                    || c instanceof HttpConnectTimeoutException) {
                return true;
            }
            if (c instanceof java.net.SocketTimeoutException
                    && c.getMessage() != null && c.getMessage().toLowerCase().contains("connect")) {
                return true;   // "Connect timed out" (simple/JDK URLConnection factory)
            }
        }
        return false;
    }

    /**
     * Short failure detail. For HTTP errors keep the status line, plus the provider's
     * ProblemDetail message when the body is application/problem+json — never the raw
     * body (which may be a stack trace), and length-capped — so what flows into the
     * reply, logs and saga_data_json stays small and safe.
     */
    private static String shortDetail(Throwable t) {
        String s;
        if (t instanceof RestClientResponseException re) {
            String d = problemDetailMessage(re);
            s = "HTTP " + re.getStatusCode() + (d == null || d.isBlank() ? "" : " - " + d);
        } else {
            s = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) + "..." : s;
    }

    /**
     * The provider's ProblemDetail {@code detail}, or {@code null}. Parsed only when
     * the body declares application/problem+json, so a proxy's HTML/text 5xx is
     * skipped outright; the catch is a last-resort guard for a malformed body of that
     * type — this helper must never throw out of {@link #shortDetail}.
     */
    private static String problemDetailMessage(RestClientResponseException re) {
        MediaType ct = re.getResponseHeaders() == null ? null : re.getResponseHeaders().getContentType();
        if (ct == null || !ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)) return null;
        try {
            ProblemDetail pd = re.getResponseBodyAs(ProblemDetail.class);
            return pd == null ? null : pd.getDetail();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record ProvisionRequest(String orderId, String subscriberId, String offerCode,
                                    Instant validFrom, Instant validUntil) {}
    private record EntitlementResponse(String externalRef, String status, Instant validFrom, Instant validUntil,
                                       String reason) {}
}
