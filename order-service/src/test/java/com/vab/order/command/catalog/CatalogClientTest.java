package com.vab.order.command.catalog;

import com.vab.observability.Correlation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * CatalogClient contract used for fail-closed price verification: a published offer maps price/type/term,
 * a 404 means "unknown or withdrawn" (null), and anything else means "catalog unavailable" (exception → 503).
 */
class CatalogClientTest {

    private MockRestServiceServer server;
    private CatalogClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CatalogClient(builder, "http://catalog");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** A published offer maps productType, termMonths and the price fields (amount, currency, priceSnapshotId). */
    @Test
    void published_offer_maps_price_type_and_term() {
        server.expect(requestTo("http://catalog/v1/offers/OTT_NETFLIX_6M")).andRespond(withSuccess("""
                {"offerCode":"OTT_NETFLIX_6M","productType":"DIGITAL_SUBSCRIPTION","amount":599,"currency":"INR",
                 "priceSnapshotId":"ps_2026_05_netflix6m","termMonths":6,"status":"PUBLISHED","name":"ignored"}""",
                MediaType.APPLICATION_JSON));

        CatalogClient.OfferDetail offer = client.resolveOffer("OTT_NETFLIX_6M");

        assertThat(offer).isEqualTo(new CatalogClient.OfferDetail("DIGITAL_SUBSCRIPTION", 6, 599L, "INR", "ps_2026_05_netflix6m"));
    }

    /** Catalog 404 (unknown or withdrawn offer) is a definite "no such offer": null, not an outage. */
    @Test
    void not_found_returns_null() {
        server.expect(requestTo("http://catalog/v1/offers/NOPE")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.resolveOffer("NOPE")).isNull();
    }

    /** A catalog 5xx means the price can't be verified: CatalogUnavailableException (the service turns it into 503). */
    @Test
    void server_error_is_catalog_unavailable() {
        server.expect(requestTo("http://catalog/v1/offers/OTT_NETFLIX_6M")).andRespond(withServerError());

        assertThatThrownBy(() -> client.resolveOffer("OTT_NETFLIX_6M"))
                .isInstanceOf(CatalogClient.CatalogUnavailableException.class);
    }

    /** Other 4xx (e.g. 401 from a misconfigured route) are not "offer unknown" either: treated as unavailable, never as a free pass. */
    @Test
    void other_client_error_is_catalog_unavailable() {
        server.expect(requestTo("http://catalog/v1/offers/OTT_NETFLIX_6M")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.resolveOffer("OTT_NETFLIX_6M"))
                .isInstanceOf(CatalogClient.CatalogUnavailableException.class);
    }

    /** The current correlation id (MDC) is forwarded so catalog's logs join the order's trail. */
    @Test
    void forwards_correlation_id() {
        MDC.put(Correlation.MDC_CORRELATION_ID, "corr-7");
        server.expect(requestTo("http://catalog/v1/offers/OTT_NETFLIX_6M"))
                .andExpect(header(Correlation.HEADER, "corr-7"))
                .andRespond(withSuccess("{\"amount\":599,\"currency\":\"INR\"}", MediaType.APPLICATION_JSON));

        client.resolveOffer("OTT_NETFLIX_6M");

        server.verify();
    }
}
