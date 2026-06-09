package com.vab.catalog.api;

import com.vab.catalog.domain.Offer;

/** Compact offer view for the eligibility-filtered list. */
public record OfferSummary(
        String offerCode,
        String name,
        String category,
        long   amount,
        String currency,
        String priceSnapshotId) {

    public static OfferSummary from(Offer o) {
        return new OfferSummary(o.getOfferCode(), o.getName(), o.getCategory(),
                o.getAmount(), o.getCurrency(), o.getPriceSnapshotId());
    }
}
