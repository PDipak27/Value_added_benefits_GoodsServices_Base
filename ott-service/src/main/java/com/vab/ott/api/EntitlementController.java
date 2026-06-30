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

        // Idempotent on orderId — a retried request returns the same entitlement.
        var existing = repo.findByOrderId(req.orderId());
        if (existing.isPresent()) {
            Entitlement e = existing.get();
            log.info("OTT provision idempotent hit: orderId={}, externalRef={}", req.orderId(), e.getExternalRef());
            return ResponseEntity.ok(new EntitlementResponse(e.getExternalRef(), e.getStatus().name()));
        }

        String externalRef = "OTT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Entitlement e = repo.save(new Entitlement(externalRef, req.orderId(), req.subscriberId(),
                req.offerCode(), req.validFrom(), req.validUntil()));
        log.info("OTT provisioned: orderId={}, externalRef={}", req.orderId(), externalRef);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new EntitlementResponse(e.getExternalRef(), e.getStatus().name()));
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

    public record ProvisionRequest(String orderId, String subscriberId, String offerCode,
                                   Instant validFrom, Instant validUntil) {}

    public record EntitlementResponse(String externalRef, String status) {}
}
