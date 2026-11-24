package com.vab.ott.api;

import com.vab.ott.domain.Entitlement;
import com.vab.ott.domain.EntitlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Provisioning API of the OTT provider (DD-27).
 *
 * <p>{@code POST /ott/v1/entitlements} provisions an entitlement and returns its
 * {@code externalRef}. Idempotent on {@code orderId}: a repeat returns the
 * existing entitlement (200) instead of creating a duplicate, so the caller's
 * in-call retries are safe.
 *
 * <p>Demo failure triggers (carried in {@code offerCode}, since that is what the
 * caller forwards): {@code OTTDOWN} → 503 (provider unavailable — caller retries
 * then parks), {@code OTTBAD} → 422 (hard rejection — caller parks, no retry).
 */
@RestController
@RequestMapping("/ott/v1/entitlements")
public class EntitlementController {

    private static final Logger log = LoggerFactory.getLogger(EntitlementController.class);

    private final EntitlementRepository repo;

    public EntitlementController(EntitlementRepository repo) {
        this.repo = repo;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<EntitlementResponse> provision(@RequestBody ProvisionRequest req) {
        String offerCode = req.offerCode() == null ? "" : req.offerCode().toUpperCase();

        if (offerCode.contains("OTTDOWN")) {
            log.warn("OTT provider DOWN (demo trigger): orderId={}, offerCode={}", req.orderId(), req.offerCode());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OTT provider temporarily unavailable");
        }
        if (offerCode.contains("OTTBAD")) {
            log.warn("OTT provider REJECTED (demo trigger): orderId={}, offerCode={}", req.orderId(), req.offerCode());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Offer not provisionable: " + req.offerCode());
        }

        // Idempotent on orderId: a retried request returns the entitlement in its current state.
        var existing = repo.findByOrderId(req.orderId());
        if (existing.isPresent()) {
            Entitlement e = existing.get();
            log.info("OTT provision idempotent hit: orderId={}, externalRef={}, status={}",
                    req.orderId(), e.getExternalRef(), e.getStatus());
            return ResponseEntity.ok(EntitlementResponse.of(e));
        }

        // Accepted, not done: the activator finishes it shortly and the partner hears via webhook (Design/06 §8).
        String externalRef = "OTT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Entitlement e = repo.save(new Entitlement(externalRef, req.orderId(), req.subscriberId(),
                req.offerCode(), req.validFrom(), req.validUntil()));
        log.info("OTT provisioning accepted (PENDING): orderId={}, externalRef={}", req.orderId(), externalRef);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(EntitlementResponse.of(e));
    }

    /**
     * DELETE /ott/v1/entitlements/{externalRef} — admin revoke (Phase 3).
     * Idempotent: 204 even when the entitlement is missing or already revoked.
     */
    @DeleteMapping("/{externalRef}")
    @Transactional
    public ResponseEntity<Void> revoke(@PathVariable String externalRef) {
        repo.findById(externalRef).ifPresentOrElse(
                e -> { e.revoke(); log.info("OTT entitlement revoked: externalRef={}", externalRef); },
                () -> log.info("OTT revoke no-op (unknown externalRef={})", externalRef));
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /ott/v1/entitlements/by-order/{orderId} — revoke whatever entitlement exists for an order
     * (Design/13 §6: the order is being refunded, possibly after a timed-out provision whose
     * externalRef the caller never received). Idempotent: 204 even when there is none.
     */
    @DeleteMapping("/by-order/{orderId}")
    @Transactional
    public ResponseEntity<Void> revokeByOrder(@PathVariable String orderId) {
        repo.findByOrderId(orderId).ifPresentOrElse(
                e -> { e.revoke(); log.info("OTT entitlement revoked by order: orderId={}, externalRef={}", orderId, e.getExternalRef()); },
                () -> log.info("OTT revoke-by-order no-op (no entitlement for orderId={})", orderId));
        return ResponseEntity.noContent().build();
    }

    public record ProvisionRequest(String orderId, String subscriberId, String offerCode,
                                   Instant validFrom, Instant validUntil) {}

    public record EntitlementResponse(String externalRef, String status, Instant validFrom, Instant validUntil,
                                      String reason) {
        static EntitlementResponse of(Entitlement e) {
            return new EntitlementResponse(e.getExternalRef(), e.getStatus().name(), e.getValidFrom(), e.getValidUntil(),
                    e.getRejectionReason());
        }
    }
    public static void main(String [] aa) {
    	byte[] a = "12345".getBytes(); byte[] b = "1234".getBytes();
    	
        // Track length mismatch without returning early
        int diff = a.length ^ b.length;

        // Iterate over the longest array to avoid leaking length via timing
        int maxLength = Math.max(a.length, b.length);
        for (int i = 0; i < maxLength; i++) {
            byte byteA = (i < a.length) ? a[i] : 0;
            byte byteB = (i < b.length) ? b[i] : 0;
            System.out.println( "byteA: "+byteA  + " byteB: "+byteB+ " diff: "+diff + " byteA ^ byteB: "+(byteA ^ byteB)  );
            diff |= byteA ^ byteB;
            System.out.println( "byteA: "+byteA  + " byteB: "+byteB+ " diff: "+diff + " byteA ^ byteB: "+(byteA ^ byteB)  );
        }

        System.out.println(diff == 0 );
    }

    
}
