package com.vab.fulfilment.ott;

import com.vab.observability.Correlation;
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

/**
 * REST client over the external {@code ott-service} (DD-27).
 *
 * <p>Provisioning is attempted with bounded <em>in-call</em> retries: a 5xx /
 * timeout / connection error is transient and retried up to {@code maxAttempts};
 * a {@code 422} is a hard rejection and is <em>not</em> retried. Either way, once
 * attempts are exhausted (or a 422 is seen) the caller gets a failed
 * {@link Result} and the order is parked in {@code FULFILMENT_FAILED} — there is
 * no auto-refund (DIGITAL_SUBSCRIPTION diverges from DD-26 here).
 */
@Component
public class OttClient {

    private static final Logger log = LoggerFactory.getLogger(OttClient.class);

    /** Outcome of a provisioning attempt; {@code provisioned} gates the rest. */
    public record Result(boolean provisioned, String externalRef, String reason, String detail) {}

    private final RestClient restClient;
    private final int maxAttempts;
    private final long retryDelayMs;

    public OttClient(RestClient.Builder builder,
                     KeycloakTokenProvider tokenProvider,
                     @Value("${ott.base-url:http://localhost:8087}") String baseUrl,
                     @Value("${ott.max-attempts:3}") int maxAttempts,
                     @Value("${ott.retry-delay-ms:200}") long retryDelayMs) {
        // §A-1: every call to the secured ott-service carries a Keycloak Bearer token.
        this.restClient   = builder.baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokenProvider.token());
                    // §C2 B-2: forward the correlation id so ott-service's logs join this order's trail.
                    String correlationId = MDC.get(Correlation.MDC_CORRELATION_ID);
                    if (correlationId != null) request.getHeaders().set(Correlation.HEADER, correlationId);
                    return execution.execute(request, body);
                })
                .build();
        this.maxAttempts  = maxAttempts;
        this.retryDelayMs = retryDelayMs;
    }

    public Result provision(String orderId, String subscriberId, String offerCode,
                            java.time.Instant validFrom, java.time.Instant validUntil) {
        String lastDetail = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                EntitlementResponse resp = restClient.post()
                        .uri("/ott/v1/entitlements")
                        .body(new ProvisionRequest(orderId, subscriberId, offerCode, validFrom, validUntil))
                        .retrieve()
                        .body(EntitlementResponse.class);
                log.info("OTT provision OK: orderId={}, externalRef={}, attempt={}",
                        orderId, resp == null ? null : resp.externalRef(), attempt);
                return new Result(true, resp == null ? null : resp.externalRef(), null, null);
            } catch (HttpClientErrorException.UnprocessableEntity e) {
                // 422 — hard rejection, never retried.
                lastDetail = shortDetail(e);
                log.warn("OTT provision REJECTED (422, no retry): orderId={}, {}", orderId, lastDetail);
                return new Result(false, null, "PROVISIONING_REJECTED", lastDetail);
            } catch (Exception e) {
                // 5xx / timeout / connection — transient, retry until attempts exhausted.
                lastDetail = shortDetail(e);
                log.warn("OTT provision attempt {}/{} failed (will retry if attempts remain): orderId={}, {}",
                        attempt, maxAttempts, orderId, lastDetail);

                if (attempt < maxAttempts) sleep();
            }
        }
        log.warn("OTT provision UNAVAILABLE after {} attempts: orderId={}", maxAttempts, orderId);
        return new Result(false, null, "PROVISIONING_UNAVAILABLE", lastDetail);
    }

    /** Admin revoke (Phase 3): DELETE the OTT entitlement. Idempotent at OTT; false on any error. */
    public boolean revoke(String externalRef) {
        try {
            restClient.delete()
                    .uri("/ott/v1/entitlements/{ref}", externalRef)
                    .retrieve()
                    .toBodilessEntity();
            log.info("OTT revoke OK: externalRef={}", externalRef);
            return true;
        } catch (Exception e) {
            log.warn("OTT revoke failed: externalRef={}, {}", externalRef, shortDetail(e));
            return false;
        }
    }

    private void sleep() {
        try { Thread.sleep(retryDelayMs); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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
                                    java.time.Instant validFrom, java.time.Instant validUntil) {}
    private record EntitlementResponse(String externalRef, String status) {}
}
