package com.vab.catalog.api;

import com.vab.catalog.domain.Offer;
import com.vab.events.common.ProductType;

/** Compact offer view for the eligibility-filtered list. */
public record OfferSummary(
        String      offerCode,
        String      name,
        ProductType productType,
        long        amount,
        String      currency,
        String      priceSnapshotId) {

    public static OfferSummary from(Offer o) {
        return new OfferSummary(o.getOfferCode(), o.getName(), o.getProductType(),
                o.getAmount(), o.getCurrency(), o.getPriceSnapshotId());
    }
}
