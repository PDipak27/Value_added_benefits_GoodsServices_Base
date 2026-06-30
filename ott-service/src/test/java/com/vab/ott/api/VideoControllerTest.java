package com.vab.ott.api;

import com.vab.ott.domain.Entitlement;
import com.vab.ott.domain.EntitlementRepository;
import com.vab.ott.domain.Video;
import com.vab.ott.domain.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §A-2 video surface: catalog listing + entitlement-gated streaming. The OIDC
 * principal is set directly on the SecurityContext (no Keycloak), so the gating
 * logic is verified deterministically.
 */
@ExtendWith(MockitoExtension.class)
class VideoControllerTest {

    @Mock VideoRepository videos;
    @Mock EntitlementRepository entitlements;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new VideoController(videos, entitlements))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private static void loginAs(String subscriberId) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("t")
                .claim("sub", "kc-" + subscriberId)
                .claim("subscriberId", subscriberId)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(new DefaultOidcUser(List.of(), idToken), "n/a"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Video video(String id, String title, String offerCode) {
        Video v = mock(Video.class);
        lenient().when(v.getId()).thenReturn(id);
        lenient().when(v.getTitle()).thenReturn(title);
        lenient().when(v.getOfferCode()).thenReturn(offerCode);
        return v;
    }

    @Test
    void catalog_lists_videos() throws Exception {
        when(videos.findAll()).thenReturn(List.of(video("vid1", "IPL Final", "OTT_HOTSTAR_3M")));

        mvc().perform(get("/v1/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("vid1"))
                .andExpect(jsonPath("$[0].offerCode").value("OTT_HOTSTAR_3M"));
    }

    @Test
    void stream_returns_playing_when_entitled() throws Exception {
        loginAs("sub-alice");
        when(videos.findById("vid1")).thenReturn(Optional.of(video("vid1", "IPL Final", "OTT_HOTSTAR_3M")));
        when(entitlements.existsBySubscriberIdAndOfferCodeAndStatus("sub-alice", "OTT_HOTSTAR_3M", Entitlement.Status.ACTIVE))
                .thenReturn(true);

        mvc().perform(get("/v1/videos/vid1/stream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Playing video: IPL Final"));
    }

    @Test
    void stream_is_403_when_not_entitled() throws Exception {
        loginAs("sub-bob");
        when(videos.findById("vid1")).thenReturn(Optional.of(video("vid1", "IPL Final", "OTT_HOTSTAR_3M")));
        when(entitlements.existsBySubscriberIdAndOfferCodeAndStatus("sub-bob", "OTT_HOTSTAR_3M", Entitlement.Status.ACTIVE))
                .thenReturn(false);

        mvc().perform(get("/v1/videos/vid1/stream"))
                .andExpect(status().isForbidden());
    }

    @Test
    void stream_is_404_for_unknown_video() throws Exception {
        loginAs("sub-alice");
        when(videos.findById("nope")).thenReturn(Optional.empty());

        mvc().perform(get("/v1/videos/nope/stream"))
                .andExpect(status().isNotFound());
    }
}
