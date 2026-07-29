package com.vab.order.command.catalog;

import org.springframework.stereotype.Component;

/**
 * LITE stub of the catalog client — catalog-service is not deployed in Lite.
 *
 * <p>The full client did a best-effort, fail-open lookup against catalog-service to
 * verify the client-sent {@code productType} and snapshot {@code termMonths}. Lite
 * keeps the same fail-open contract but without any network call: {@link #resolveOffer}
 * always returns {@code null}, so the caller falls back to the client-sent
 * {@code productType} with a null term (exactly the full build's catalog-unreachable
 * path). See Design/lite-cutlist.md.
 */
@Component
public class CatalogClient {

    /** Always {@code null} in Lite — caller uses the client-sent productType, term=null. */
    public OfferDetail resolveOffer(String offerCode) {
        return null;
    }

    /** Minimal projection of the catalog Offer JSON — the fields the order side uses. */
    public record OfferDetail(String productType, Integer termMonths) {}
}
