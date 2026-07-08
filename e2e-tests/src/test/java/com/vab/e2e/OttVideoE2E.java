package com.vab.e2e;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

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
     * Redirects are followed manually and cookies carried in an explicit name→value jar
     * (CookieFilter drops the {@code path=/realms/vab/} auth-session cookie on the POST).
     * On successful login Spring rotates the session id, so the authenticated JSESSIONID
     * is captured from the callback's 302. Depends on Keycloak's login-page markup.
     * Pre-req: re-imported realm with user 'alice'/'alice' (subscriberId=sub-alice),
     * who owns OTT_HOTSTAR_3M via the V4 seed.
     */
    @Test
    void subscriber_login_then_stream_is_gated() {
        Map<String, String> jar = new HashMap<>();

        // 1. Start login at OTT → 302 to Keycloak's auth endpoint (sets OTT JSESSIONID).
        Response r1 = given().baseUri(OTT).redirects().follow(false).cookies(jar)
                .when().get("/oauth2/authorization/keycloak");
        jar.putAll(r1.getCookies());
        String kcAuthUrl = r1.getHeader("Location");

        // 2. GET the auth endpoint → 200 login page (sets AUTH_SESSION_ID / KC_RESTART).
        // urlEncodingEnabled(false): the URL is already encoded by the server; re-encoding
        // would double-encode 'state' (its trailing '=' → %3D → %253D) and break the flow.
        Response r2 = given().redirects().follow(false).cookies(jar).urlEncodingEnabled(false)
                .when().get(kcAuthUrl);
        jar.putAll(r2.getCookies());
        String action = r2.asString()
                .replaceAll("(?s).*<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\".*", "$1")
                .replace("&amp;", "&");

        // 3. POST credentials → 302 back to the OTT callback with the auth code.
        Response r3 = given().redirects().follow(false).cookies(jar).urlEncodingEnabled(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("username", "alice").formParam("password", "alice")
                .when().post(action);
        jar.putAll(r3.getCookies());
        String callbackUrl = r3.getHeader("Location");

        // 4. GET the callback (carries JSESSIONID) → OTT exchanges the code → authenticated session.
        Response r4 = given().redirects().follow(false).cookies(jar).urlEncodingEnabled(false)
                .when().get(callbackUrl);
        jar.putAll(r4.getCookies());

        // --- diagnostics: which step loses the session? ---
        System.out.println("[A2-DIAG] r2.status=" + r2.getStatusCode()
                + " action=" + (action.length() > 90 ? action.substring(0, 90) : action));
        //System.out.println("[A2-DIAG] r3.status=" + r3.getStatusCode() + " callback=" + callbackUrl);
        //System.out.println("[A2-DIAG] r4.status=" + r4.getStatusCode() + " location=" + r4.getHeader("Location"));
        //System.out.println("[A2-DIAG] r4.cookies=" + r4.getCookies());
        //System.out.println("[A2-DIAG] jar keys=" + jar.keySet()
               // + " JSESSIONID=" + (jar.containsKey("JSESSIONID")));

        // 5. Entitled title → "Playing video: …".
        given().baseUri(OTT).redirects().follow(false).cookies(jar)
                .when().get("/v1/videos/vid_hotstar_ipl/stream")
                .then().statusCode(200).body("message", containsString("Playing video"));

        // 6. Title for an offer alice does not own → 403.
        given().baseUri(OTT).redirects().follow(false).cookies(jar)
                .when().get("/v1/videos/vid_netflix_film/stream")
                .then().statusCode(403);
    }
}
