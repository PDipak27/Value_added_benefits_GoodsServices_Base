package com.vab.order.it;

import com.jayway.jsonpath.JsonPath;
import com.vab.order.command.catalog.CatalogClient;
import com.vab.order.command.domain.Order;
import com.vab.order.query.document.EntitlementView;
import com.vab.order.query.document.OrderView;
import com.vab.order.query.repository.EntitlementViewRepository;
import com.vab.order.query.repository.OrderViewRepository;
import com.vab.order.saga.PlaceOrderSaga;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Order placement through the real API + security chain into real Postgres/Mongo (§A-3, DD-14/15):
 * the price is verified against catalog (409 / 422 / 503), then one transaction writes the order row,
 * the OrderPlaced outbox row, the saga instance + its first command, and the idempotency key; GET then reads it back (read-your-writes fallback before projection).
 *
 * <p>Each test uses a fresh subscriber id, so Postgres rows from other tests (or reused containers)
 * never interfere; the Mongo read models are wiped per test.
 */
class OrderPlacementIT extends AbstractOrderIntegrationTest {

    private static final String OFFER = "OTT_NETFLIX_6M";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderViewRepository views;
    @Autowired EntitlementViewRepository entitlements;

    private String subscriber;

    @BeforeEach
    void setUp() {
        subscriber = "sub-it-" + UUID.randomUUID().toString().substring(0, 8);
        views.deleteAll();
        entitlements.deleteAll();
        when(catalogClient.resolveOffer(OFFER)).thenReturn(
                new CatalogClient.OfferDetail("DIGITAL_SUBSCRIPTION", 6, 599L, "INR", "ps_2026_05_netflix6m"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ResultActions place(String sub, String idempotencyKey, String clientProductType) throws Exception {
        return place(sub, idempotencyKey, clientProductType, 599);
    }

    private ResultActions place(String sub, String idempotencyKey, String clientProductType, long amount) throws Exception {
        String body = """
                {"offerCode":"%s","productType":"%s","priceSnapshotId":"ps_2026_05_netflix6m",
                 "amount":%d,"currency":"INR","billingMode":"PAY_NOW"}""".formatted(OFFER, clientProductType, amount);
        return mvc.perform(post("/v1/orders")
                .with(jwt().jwt(j -> j.claim("subscriberId", sub)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String placeOk(String sub, String idempotencyKey) throws Exception {
        String json = place(sub, idempotencyKey, "DIGITAL_SUBSCRIPTION")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.orderId");
    }

    private Map<String, Object> orderRow(String orderId) {
        return jdbc.queryForMap("SELECT * FROM orders.orders WHERE order_id = ?", orderId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** OrderPlaced outbox rows for this order (domain events go to the aggregate-type topic). */
    private List<Map<String, Object>> orderPlacedOutboxRows(String orderId) {
        return jdbc.queryForList(
                "SELECT headers, payload, published FROM eventuate.message WHERE destination = ? AND headers LIKE ? AND headers LIKE '%OrderPlaced%'",
                Order.AGGREGATE_TYPE, "%" + orderId + "%");
    }

    // ── placement: one transaction, four writes ─────────────────────────────

    /** A placement persists the PLACED aggregate for the token's subscriber, with catalog's productType and the 6-month term snapshot. */
    @Test
    void placement_persists_placed_order_for_token_subscriber() throws Exception {
        String orderId = placeOk(subscriber, UUID.randomUUID().toString());

        Map<String, Object> row = orderRow(orderId);
        assertThat(row.get("status")).isEqualTo("PLACED");
        assertThat(row.get("subscriber_id")).isEqualTo(subscriber);
        assertThat(row.get("product_type")).isEqualTo("DIGITAL_SUBSCRIPTION");
        assertThat(row.get("billing_mode")).isEqualTo("PAY_NOW");
        assertThat(((Number) row.get("term_months")).intValue()).isEqualTo(6);
        assertThat(((Number) row.get("version")).longValue()).isZero();
    }

    /** OrderPlaced is written to the Tram outbox in the same tx, unpublished (no CDC in ITs), carrying the subscriber. */
    @Test
    void placement_writes_order_placed_to_outbox() throws Exception {
        String orderId = placeOk(subscriber, UUID.randomUUID().toString());

        List<Map<String, Object>> rows = orderPlacedOutboxRows(orderId);
        assertThat(rows).hasSize(1);
        assertThat((String) rows.get(0).get("payload")).contains(subscriber, OFFER);
        assertThat(((Number) rows.get(0).get("published")).intValue()).isZero();
    }

    /** The PlaceOrderSaga instance is persisted and its first command (PAY_NOW → reserve) is in the outbox for inventory. */
    @Test
    void placement_starts_saga_and_sends_first_command_to_inventory() throws Exception {
        String orderId = placeOk(subscriber, UUID.randomUUID().toString());

        List<Map<String, Object>> sagas = jdbc.queryForList(
                "SELECT saga_type, saga_id FROM eventuate.saga_instance WHERE saga_data_json LIKE ?", "%" + orderId + "%");
        assertThat(sagas).hasSize(1);
        assertThat((String) sagas.get(0).get("saga_type")).contains("PlaceOrderSaga");

        String sagaId = (String) sagas.get(0).get("saga_id");
        assertThat(count("SELECT count(*) FROM eventuate.message WHERE destination = ? AND headers LIKE ?",
                PlaceOrderSaga.INVENTORY_CHANNEL, "%" + sagaId + "%")).isEqualTo(1);
    }

    /** The idempotency key is stored against (subscriber, key) → orderId in the same transaction. */
    @Test
    void placement_stores_idempotency_key() throws Exception {
        String key = UUID.randomUUID().toString();
        String orderId = placeOk(subscriber, key);

        assertThat(count("SELECT count(*) FROM orders.idempotency_keys WHERE subscriber_id = ? AND idempotency_key = ? AND order_id = ?",
                subscriber, key, orderId)).isEqualTo(1);
    }

    // ── idempotency + uniqueness ────────────────────────────────────────────

    /** Replaying the same Idempotency-Key returns the same orderId with no second order, outbox event or saga. */
    @Test
    void replay_with_same_idempotency_key_has_no_side_effects() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = placeOk(subscriber, key);
        String second = placeOk(subscriber, key);

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM orders.orders WHERE subscriber_id = ?", subscriber)).isEqualTo(1);
        assertThat(orderPlacedOutboxRows(first)).hasSize(1);
        assertThat(count("SELECT count(*) FROM eventuate.saga_instance WHERE saga_data_json LIKE ?", "%" + first + "%")).isEqualTo(1);
    }

    /** Idempotency keys are scoped per subscriber: the same key from another subscriber places a separate order. */
    @Test
    void same_key_from_another_subscriber_is_a_new_order() throws Exception {
        String key = UUID.randomUUID().toString();
        String mine = placeOk(subscriber, key);
        String theirs = placeOk(subscriber + "-b", key);

        assertThat(theirs).isNotEqualTo(mine);
        assertThat(orderRow(theirs).get("subscriber_id")).isEqualTo(subscriber + "-b");
    }

    /** Uniqueness layer 1: a subscriber already holding an ACTIVE entitlement for the offer gets 409 and nothing is written. */
    @Test
    void already_entitled_subscriber_gets_409() throws Exception {
        EntitlementView active = new EntitlementView();
        active.setEntitlementId("ent_" + UUID.randomUUID());
        active.setSourceOrderId("ord_previous");
        active.setSubscriberId(subscriber);
        active.setOfferCode(OFFER);
        active.setStatus("ACTIVE");
        entitlements.save(active);

        place(subscriber, UUID.randomUUID().toString(), "DIGITAL_SUBSCRIPTION").andExpect(status().isConflict());

        assertThat(count("SELECT count(*) FROM orders.orders WHERE subscriber_id = ?", subscriber)).isZero();
    }

    // ── catalog verification (price fail-closed) ────────────────────────────────────────

    /** Catalog is authoritative: a mismatching client productType is replaced by catalog's, in the row and in OrderPlaced. */
    @Test
    void catalog_product_type_overrides_client_value() throws Exception {
        String json = place(subscriber, UUID.randomUUID().toString(), "PHYSICAL_GOOD")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(json, "$.orderId");

        assertThat(orderRow(orderId).get("product_type")).isEqualTo("DIGITAL_SUBSCRIPTION");
        assertThat((String) orderPlacedOutboxRows(orderId).get(0).get("payload")).contains("DIGITAL_SUBSCRIPTION");
    }

    /** Price tampering: an amount below the catalog price is rejected with 409 and nothing is written (no row, no outbox event). */
    @Test
    void tampered_price_is_rejected_409_and_nothing_written() throws Exception {
        place(subscriber, UUID.randomUUID().toString(), "DIGITAL_SUBSCRIPTION", 1).andExpect(status().isConflict());

        assertThat(count("SELECT count(*) FROM orders.orders WHERE subscriber_id = ?", subscriber)).isZero();
        assertThat(count("SELECT count(*) FROM eventuate.message WHERE payload LIKE ?", "%" + subscriber + "%")).isZero();
    }

    /** An offer catalog doesn't publish (unknown or withdrawn) is rejected with 422 at placement. */
    @Test
    void unknown_offer_is_rejected_422() throws Exception {
        when(catalogClient.resolveOffer(OFFER)).thenReturn(null);

        place(subscriber, UUID.randomUUID().toString(), "DIGITAL_SUBSCRIPTION").andExpect(status().isUnprocessableEntity());

        assertThat(count("SELECT count(*) FROM orders.orders WHERE subscriber_id = ?", subscriber)).isZero();
    }

    /** Fail-closed: with catalog unreachable the price can't be verified, so placement is 503 and nothing is written. */
    @Test
    void catalog_down_rejects_with_503() throws Exception {
        when(catalogClient.resolveOffer(OFFER))
                .thenThrow(new CatalogClient.CatalogUnavailableException(OFFER, new RuntimeException("connect timed out")));

        place(subscriber, UUID.randomUUID().toString(), "DIGITAL_SUBSCRIPTION").andExpect(status().isServiceUnavailable());

        assertThat(count("SELECT count(*) FROM orders.orders WHERE subscriber_id = ?", subscriber)).isZero();
        assertThat(count("SELECT count(*) FROM orders.idempotency_keys WHERE subscriber_id = ?", subscriber)).isZero();
    }

    /** The order records catalog's priceSnapshotId (the price actually verified), whatever the client sent. */
    @Test
    void order_records_catalog_price_snapshot() throws Exception {
        String orderId = placeOk(subscriber, UUID.randomUUID().toString());

        assertThat(orderRow(orderId).get("price_snapshot_id")).isEqualTo("ps_2026_05_netflix6m");
        assertThat(((Number) orderRow(orderId).get("amount")).longValue()).isEqualTo(599);
    }

    // ── read side right after the write (DD-15) ─────────────────────────────

    /** Before any projection exists, GET /v1/orders/{id} serves the owner from Postgres (read-your-writes), not 404. */
    @Test
    void get_right_after_post_falls_back_to_write_store() throws Exception {
        String orderId = placeOk(subscriber, UUID.randomUUID().toString());
        assertThat(views.findById(orderId)).isEmpty();   // nothing projected (no CDC)

        mvc.perform(get("/v1/orders/" + orderId).with(jwt().jwt(j -> j.claim("subscriberId", subscriber))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.timeline[0].status").value("PLACED"));
    }

    /** The fallback keeps object-level authorization: another subscriber gets 404, an admin gets 200. */
    @Test
    void fallback_read_enforces_ownership() throws Exception {
        String orderId = placeOk(subscriber, UUID.randomUUID().toString());

        mvc.perform(get("/v1/orders/" + orderId).with(jwt().jwt(j -> j.claim("subscriberId", "sub-intruder"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/orders/" + orderId).with(jwt()
                        .jwt(j -> j.subject("ops-admin").claim("realm_access", Map.of("roles", List.of("vab-admin"))))
                        .authorities(new SimpleGrantedAuthority("ROLE_vab-admin"))))
                .andExpect(status().isOk());
    }

    /** Once the order is projected, the Mongo read model wins over the write store (here: CONFIRMED vs Postgres PLACED). */
    @Test
    void projected_view_takes_precedence_over_fallback() throws Exception {
        String orderId = placeOk(subscriber, UUID.randomUUID().toString());
        OrderView projected = new OrderView();
        projected.setOrderId(orderId);
        projected.setSubscriberId(subscriber);
        projected.setOfferCode(OFFER);
        projected.setStatus("CONFIRMED");
        projected.setVersion(1);
        projected.addTimelineEntry(Instant.now(), "CONFIRMED");
        views.save(projected);

        mvc.perform(get("/v1/orders/" + orderId).with(jwt().jwt(j -> j.claim("subscriberId", subscriber))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    /** The list endpoint is read-model only (the CQRS boundary): a just-placed, unprojected order is not listed yet. */
    @Test
    void list_is_read_model_only() throws Exception {
        placeOk(subscriber, UUID.randomUUID().toString());

        mvc.perform(get("/v1/orders").with(jwt().jwt(j -> j.claim("subscriberId", subscriber))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
