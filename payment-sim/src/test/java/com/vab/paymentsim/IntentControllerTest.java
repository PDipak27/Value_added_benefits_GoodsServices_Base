package com.vab.paymentsim;

import com.vab.paymentsim.IntentController.CreateIntentRequest;
import com.vab.paymentsim.IntentController.IntentResponse;
import com.vab.paymentsim.PaymentIntent.Status;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Merchant API: idempotent create, demo triggers, and which transitions each call allows. */
class IntentControllerTest {

    private final PspProperties props = new PspProperties();
    private final IntentStore store = new IntentStore(props);
    private final IntentController api = new IntentController(store, props);

    private IntentResponse create(String key, long amount) {
        ResponseEntity<?> r = api.create(key, new CreateIntentRequest("ord-1", amount, "INR"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (IntentResponse) r.getBody();
    }

    private PaymentIntent intent(IntentResponse r) {
        return store.find(r.id()).orElseThrow();
    }

    private static Object error(ResponseEntity<?> r) {
        return ((Map<?, ?>) r.getBody()).get("error");
    }

    @Test
    void create_returns_a_checkout_link_with_the_client_secret() {
        IntentResponse r = create("create-ord-1", 500);

        assertThat(r.status()).isEqualTo("REQUIRES_PAYMENT");
        assertThat(r.checkoutUrl()).startsWith("http://localhost:8091/checkout/" + r.id() + "?cs=cs_");
        assertThat(r.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void the_same_idempotency_key_returns_the_same_intent() {
        assertThat(create("create-ord-1", 500).id()).isEqualTo(create("create-ord-1", 500).id());
        assertThat(create("create-ord-2", 500).id()).isNotEqualTo(create("create-ord-1", 500).id());
    }

    @Test
    void amount_503_simulates_a_provider_outage() {
        ResponseEntity<?> r = api.create("k", new CreateIntentRequest("ord-1", IntentController.PROVIDER_DOWN_AMOUNT, "INR"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void invalid_request_is_400() {
        assertThat(api.create("k", new CreateIntentRequest("ord-1", 0, "INR")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void capture_needs_an_authorisation_and_is_idempotent() {
        IntentResponse r = create("k", 500);
        assertThat(api.capture(r.id()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        intent(r).setStatus(Status.AUTHORIZED);
        assertThat(api.capture(r.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.capture(r.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(intent(r).getStatus()).isEqualTo(Status.CAPTURED);
    }

    @Test
    void amount_777_declines_the_capture_and_keeps_the_authorisation() {
        IntentResponse r = create("k", IntentController.CAPTURE_DECLINE_AMOUNT);
        intent(r).setStatus(Status.AUTHORIZED);

        ResponseEntity<?> res = api.capture(r.id());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(error(res)).isEqualTo("card_declined");
        assertThat(intent(r).getStatus()).isEqualTo(Status.AUTHORIZED);
    }

    @Test
    void cancel_closes_an_unpaid_intent_and_voids_an_authorisation() {
        IntentResponse unpaid = create("k1", 500);
        api.cancel(unpaid.id());
        assertThat(intent(unpaid).getStatus()).isEqualTo(Status.CANCELED);
        assertThat(api.cancel(unpaid.id()).getStatusCode()).isEqualTo(HttpStatus.OK);   // idempotent

        IntentResponse paid = create("k2", 500);
        intent(paid).setStatus(Status.AUTHORIZED);
        api.cancel(paid.id());
        assertThat(intent(paid).getStatus()).isEqualTo(Status.VOIDED);
    }

    @Test
    void a_captured_payment_cannot_be_voided_only_refunded() {
        IntentResponse r = create("k", 500);
        intent(r).setStatus(Status.CAPTURED);

        assertThat(api.cancel(r.id()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(api.refund(r.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.refund(r.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(intent(r).getStatus()).isEqualTo(Status.REFUNDED);
    }

    @Test
    void unknown_intent_is_404() {
        assertThat(api.get("pi_nope").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.capture("pi_nope").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
