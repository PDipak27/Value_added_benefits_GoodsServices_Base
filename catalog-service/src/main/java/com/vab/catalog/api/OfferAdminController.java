package com.vab.catalog.api;

import com.vab.catalog.domain.Offer;
import com.vab.catalog.domain.OfferAdminService;
import com.vab.catalog.domain.OfferStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Catalog admin/write surface. Each mutation evicts the Redis read-caches inline
 * (DD-17) via {@link OfferAdminService}, so a change is visible on the next read.
 *
 * <p>Lives under {@code /v1/offers} so the existing gateway route
 * ({@code /v1/offers/**} → catalog) covers it. The gateway requires the
 * {@code vab-admin} realm role for these writes (only GET and {@code :evaluate} are
 * public at the edge); catalog-service itself is internal and does not re-check.
 */
@RestController
@RequestMapping("/v1/offers")
public class OfferAdminController {

    private final OfferAdminService admin;

    public OfferAdminController(OfferAdminService admin) {
        this.admin = admin;
    }

    /** Create (and publish) an offer. {@code offerCode} comes from the body. */
    @PostMapping
    public ResponseEntity<Offer> create(@RequestBody OfferRequest req) {
        Offer saved = admin.upsert(req.toOffer(req.offerCode(), OfferStatus.PUBLISHED));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** Replace an existing offer (incl. price/eligibility), keeping it published. */
    @PutMapping("/{offerCode}")
    public ResponseEntity<Offer> update(@PathVariable String offerCode,
                                        @RequestBody OfferRequest req) {
        Offer saved = admin.upsert(req.toOffer(offerCode, OfferStatus.PUBLISHED));
        return ResponseEntity.ok(saved);
    }

    /** Withdraw an offer. 404 if it does not exist. */
    @PostMapping("/{offerCode}:withdraw")
    public ResponseEntity<Offer> withdraw(@PathVariable String offerCode) {
        return admin.withdraw(offerCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
