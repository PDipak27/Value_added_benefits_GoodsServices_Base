package com.vab.catalog.api;

import com.vab.catalog.domain.KycLevel;
import com.vab.catalog.domain.Offer;
import com.vab.catalog.domain.OfferStatus;
import com.vab.catalog.domain.PlanTier;
import com.vab.events.common.ProductType;

/**
 * Write payload for the catalog admin API. {@code offerCode} is taken from the
 * path on update; on create it may be supplied in the body. Status is controlled
 * by the endpoint (create/update publish; {@code :withdraw} withdraws), so it is
 * not part of the request body.
 */
public record OfferRequest(
        String      offerCode,
        String      name,
        String      description,
        ProductType productType,
        long        amount,
        String      currency,
        String      priceSnapshotId,
        PlanTier    minPlanTier,
        String      allowedRegions,
        Integer     maxDeviceAgeMonths,
        KycLevel    minKycLevel) {

    /** Build a domain {@link Offer} with the given code and status. */
    public Offer toOffer(String code, OfferStatus status) {
        return new Offer(code, name, description, productType, amount, currency,
                priceSnapshotId, status, minPlanTier, allowedRegions,
                maxDeviceAgeMonths, minKycLevel);
    }
}
