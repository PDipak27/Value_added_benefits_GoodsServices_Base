package com.vab.ott.api;

import com.vab.ott.domain.Entitlement;
import com.vab.ott.domain.EntitlementRepository;
import com.vab.ott.domain.Video;
import com.vab.ott.domain.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * OTT subscriber video surface (§A-2). The caller is authenticated by the OIDC login
 * chain (Authorization Code + PKCE via Keycloak); the subscriber identity comes from
 * the {@code subscriberId} claim. Streaming is gated on the subscriber holding an
 * ACTIVE local entitlement for the video's offer — there is no real media, so a
 * successful stream just returns a "Playing video: …" message.
 */
@RestController
@RequestMapping("/v1/videos")
public class VideoController {

    private static final Logger log = LoggerFactory.getLogger(VideoController.class);

    private final VideoRepository       videos;
    private final EntitlementRepository entitlements;

    public VideoController(VideoRepository videos, EntitlementRepository entitlements) {
        this.videos       = videos;
        this.entitlements = entitlements;
    }

    /** GET /v1/videos — the catalog (any logged-in subscriber). */
    @GetMapping
    public List<VideoSummary> list() {
        return videos.findAll().stream()
                .map(v -> new VideoSummary(v.getId(), v.getTitle(), v.getOfferCode()))
                .toList();
    }

    /** GET /v1/videos/{id}/stream — entitlement-gated; 404 unknown, 403 if not entitled. */
    @GetMapping("/{id}/stream")
    public StreamResponse stream(@PathVariable String id, @AuthenticationPrincipal OidcUser subscriber) {
        Video video = videos.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown video: " + id));

        String subscriberId = subscriber == null ? null : subscriber.getClaimAsString("subscriberId");
        boolean entitled = subscriberId != null && entitlements.existsBySubscriberIdAndOfferCodeAndStatus(
                subscriberId, video.getOfferCode(), Entitlement.Status.ACTIVE);

        if (!entitled) {
            log.info("Stream DENIED: subscriberId={}, video={}, offer={}", subscriberId, id, video.getOfferCode());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No active entitlement for offer " + video.getOfferCode());
        }
        log.info("Stream OK: subscriberId={}, video={}", subscriberId, id);
        return new StreamResponse("Playing video: " + video.getTitle());
    }

    public record VideoSummary(String id, String title, String offerCode) {}
    public record StreamResponse(String message) {}
}
