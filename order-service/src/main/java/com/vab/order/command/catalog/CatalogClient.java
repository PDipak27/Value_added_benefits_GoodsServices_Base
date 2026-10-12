package com.vab.order.command.catalog;

import com.vab.observability.Correlation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Thin client over catalog-service, the source of truth for an offer's price and
 * product type at order placement (Design/09, Q1(b)+verify).
 *
 * <p>Price integrity is <em>fail-closed</em> (DD-32, replacing the earlier best-effort
 * verify): the client-sent amount is only accepted if it matches catalog, so an
 * order cannot be placed while catalog is unreachable. {@link #resolveOffer} therefore
 * distinguishes "offer unknown / withdrawn" ({@code null}, catalog answered 404) from
 * "catalog unavailable" ({@link CatalogUnavailableException}).
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient restClient;

    public CatalogClient(RestClient.Builder builder,
                         @Value("${catalog.base-url:http://localhost:8085}") String baseUrl) {
        // §C2 B-2: forward the correlation id so catalog-service's logs join this order's trail.
        this.restClient = builder.baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(Correlation.MDC_CORRELATION_ID);
                    if (correlationId != null) request.getHeaders().set(Correlation.HEADER, correlationId);
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * The authoritative offer: {@code productType}, {@code termMonths} (snapshotted for
     * the entitlement's validity window) and the current price.
     *
     * @return the published offer, or {@code null} if catalog answered 404 (unknown or withdrawn)
     * @throws CatalogUnavailableException on any other failure (timeout, connection, 5xx)
     */
    public OfferDetail resolveOffer(String offerCode) {
        try {
            return restClient.get()
                    .uri("/v1/offers/{offerCode}", offerCode)
                    .retrieve()
                    .body(OfferDetail.class);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Catalog has no published offer for offerCode={}", offerCode);
            return null;
        } catch (Exception e) {
            log.warn("Catalog lookup failed for offerCode={} ({}: {})",
                    offerCode, e.getClass().getSimpleName(), e.getMessage());
            throw new CatalogUnavailableException(offerCode, e);
        }
    }

    /** Minimal projection of the catalog Offer JSON — the fields the order side uses. */
    public record OfferDetail(String productType, Integer termMonths,
                              Long amount, String currency, String priceSnapshotId) {}

    /** Catalog could not be reached, so the offer's price cannot be verified. */
    public static class CatalogUnavailableException extends RuntimeException {
        private static final long serialVersionUID = -5838152020395197347L;

		public CatalogUnavailableException(String offerCode, Throwable cause) {
            super("Catalog unavailable for offerCode=" + offerCode, cause);
        }
    }
}
