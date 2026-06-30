package com.vab.e2e;

import io.restassured.filter.cookie.CookieFilter;
import io.restassured.response.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * §A-2: OTT's video surface is OIDC-login protected (Authorization Code + PKCE via
 * Keycloak) and streaming is entitlement-gated.
 */
class OttVideoE2E extends E2EBase {

    /** Robust: an unauthenticated browser request is redirected into the login flow. */
    @Test
    void videos_require_login_and_redirect_to_keycloak() {
        given().baseUri(OTT).redirects().follow(false)
                .when().get("/v1/videos")
                .then().statusCode(302)
                .header("Location", containsString("/oauth2/authorization/keycloak"));
    }

    /**
     * Full Authorization Code + PKCE browser login, then entitlement-gated streaming.
     * Disabled by default: it scrapes Keycloak's login form, whose markup can vary by
     * Keycloak version — enable locally to exercise the real flow end-to-end.
     * Pre-req: subscriber 'sub-alice' (seeded user 'alice') owns OTT_HOTSTAR_3M.
     */
    @Test
    @Disabled("Full browser login — enable locally; depends on Keycloak login-page markup.")
    void subscriber_login_then_stream_is_gated() {
        CookieFilter cookies = new CookieFilter();

        // 1. Kick off login → follow into Keycloak's login page.
        Response loginPage = given().baseUri(OTT).filter(cookies).redirects().follow(true)
                .when().get("/oauth2/authorization/keycloak");
        String action = loginPage.asString()
                .replaceAll("(?s).*<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\".*", "$1")
                .replace("&amp;", "&");

        // 2. Submit credentials → 302 back to the OTT callback → session established.
        given().filter(cookies).redirects().follow(true)
                .contentType("application/x-www-form-urlencoded")
                .formParam("username", "alice").formParam("password", "alice")
                .when().post(action)
                .then().statusCode(anyOf(is(200), is(302)));

        // 3. Entitled title → "Playing video: …".
        given().baseUri(OTT).filter(cookies)
                .when().get("/v1/videos/vid_hotstar_ipl/stream")
                .then().statusCode(200).body("message", containsString("Playing video"));

        // 4. Title for an offer alice does not own → 403.
        given().baseUri(OTT).filter(cookies)
                .when().get("/v1/videos/vid_netflix_film/stream")
                .then().statusCode(403);
    }
}
