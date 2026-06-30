package com.vab.order.command.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin, <em>fail-open</em> client over catalog-service used to verify the
 * client-sent {@code productType} at order placement (Design/09, Q1(b)+verify).
 *
 * <p>Philosophy (DD-20): catalog is the source of truth, but a placement must not
 * be blocked by a transient catalog outage. So {@link #resolveOffer} is
 * best-effort: if catalog is reachable it returns the authoritative offer (whose
 * productType the caller uses in preference to the client-sent one, and whose
 * termMonths is snapshotted); if not, it returns {@code null} and the caller
 * falls back to the client-sent productType with a null term.
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient restClient;

    public CatalogClient(RestClient.Builder builder,
                         @Value("${catalog.base-url:http://localhost:8085}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Best-effort authoritative offer projection — {@code productType} (verified
     * against the client-sent value) and {@code termMonths} (snapshotted for the
     * entitlement's validity window).
     *
     * @return the catalog offer, or {@code null} if catalog is unreachable / the
     *         offer is absent (caller falls back to client-sent values; term=null).
     */
    public OfferDetail resolveOffer(String offerCode) {
        try {
            OfferDetail offer = restClient.get()
                    .uri("/v1/offers/{offerCode}", offerCode)
                    .retrieve()
                    .body(OfferDetail.class);
            if (offer == null) {
                log.warn("Catalog returned no offer for offerCode={} — caller will fall back", offerCode);
            }
            return offer;
        } catch (Exception e) {
            log.warn("Catalog verify failed for offerCode={} ({}: {}) — caller will fall back",
                    offerCode, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** Minimal projection of the catalog Offer JSON — the fields the order side uses. */
    public record OfferDetail(String productType, Integer termMonths) {}
}
