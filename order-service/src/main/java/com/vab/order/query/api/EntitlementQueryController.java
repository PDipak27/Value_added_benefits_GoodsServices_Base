package com.vab.order.query.api;

import com.vab.order.query.document.EntitlementView;
import com.vab.order.query.repository.EntitlementViewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

import static com.vab.order.command.api.OrderCommandController.subscriberId;

/**
 * "My Benefits" read API (§B1). Returns the subscriber's ACTIVE entitlements from
 * the {@code entitlements_v1} projection, mapped to a DTO (storage shape is never
 * exposed). Subject is the {@code subscriberId} JWT claim (§A-3/A5).
 */
@RestController
@RequestMapping("/v1/entitlements")
public class EntitlementQueryController {

    private static final Logger log = LoggerFactory.getLogger(EntitlementQueryController.class);

    private final EntitlementViewRepository repo;

    public EntitlementQueryController(EntitlementViewRepository repo) {
        this.repo = repo;
    }

    /** GET /v1/entitlements — active benefits for the authenticated subscriber (JWT claim). */
    @GetMapping
    public List<EntitlementDto> myBenefits(@AuthenticationPrincipal Jwt jwt) {
        String subscriberId = subscriberId(jwt);
        log.info("GET /v1/entitlements (subscriberId={})", subscriberId);
        List<EntitlementView> active = repo.findBySubscriberIdAndStatus(subscriberId, "ACTIVE");
        log.info("Found {} active entitlement(s) for subscriberId={}", active.size(), subscriberId);
        return active.stream().map(EntitlementQueryController::toDto).toList();
    }

    private static EntitlementDto toDto(EntitlementView e) {
        return new EntitlementDto(e.getOfferCode(), e.getProductType(), e.getStatus(),
                e.getExternalRef(), e.getActivationKey(), e.getActivatedAt(), e.getValidUntil());
    }

    public record EntitlementDto(String offerCode, String productType, String status,
                                 String externalRef, String activationKey,
                                 Instant activatedAt, Instant validUntil) {}
}
