package com.vab.paymentsim;

import com.vab.paymentsim.PaymentIntent.Status;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Hosted checkout: the client secret guards it, approve needs the OTP, and each decision sends one webhook. */
class CheckoutControllerTest {

    private final PspProperties props = new PspProperties();
    private final IntentStore store = new IntentStore(props);
    private final WebhookDispatcher webhooks = mock(WebhookDispatcher.class);
    private final CheckoutController checkout = new CheckoutController(store, webhooks);

    private PaymentIntent newIntent() {
        return store.create("k-" + System.nanoTime(), "ord-<1>", 500, "INR");
    }

    private ResponseEntity<String> decide(PaymentIntent i, String decision, String otp) {
        return checkout.decide(i.getId(), i.getClientSecret(), decision, otp);
    }

    @Test
    void page_shows_the_amount_and_escapes_merchant_data() {
        PaymentIntent i = newIntent();

        ResponseEntity<String> page = checkout.page(i.getId(), i.getClientSecret());

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("₹500").contains("ord-&lt;1&gt;").doesNotContain("ord-<1>");
    }

    @Test
    void wrong_client_secret_is_404_and_nothing_changes() {
        PaymentIntent i = newIntent();

        assertThat(checkout.page(i.getId(), "cs_wrong").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(checkout.decide(i.getId(), "cs_wrong", "approve", CheckoutController.OTP).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(i.getStatus()).isEqualTo(Status.REQUIRES_PAYMENT);
        verifyNoInteractions(webhooks);
    }

    @Test
    void approve_with_the_otp_authorises_and_sends_one_webhook() {
        PaymentIntent i = newIntent();

        assertThat(decide(i, "approve", CheckoutController.OTP).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(i.getStatus()).isEqualTo(Status.AUTHORIZED);
        verify(webhooks).publish("payment.authorized", i);
    }

    @Test
    void wrong_otp_is_400_and_the_customer_can_retry() {
        PaymentIntent i = newIntent();

        assertThat(decide(i, "approve", "000000").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(i.getStatus()).isEqualTo(Status.REQUIRES_PAYMENT);
        verifyNoInteractions(webhooks);

        assertThat(decide(i, "approve", CheckoutController.OTP).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void decline_fails_the_intent_with_a_reason() {
        PaymentIntent i = newIntent();

        decide(i, "decline", null);

        assertThat(i.getStatus()).isEqualTo(Status.FAILED);
        assertThat(i.getFailureReason()).isEqualTo("customer_declined");
        verify(webhooks).publish("payment.failed", i);
    }

    @Test
    void a_second_decision_is_refused() {
        PaymentIntent i = newIntent();
        decide(i, "approve", CheckoutController.OTP);

        assertThat(decide(i, "decline", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(i.getStatus()).isEqualTo(Status.AUTHORIZED);
        verify(webhooks, times(1)).publish(anyString(), any());
    }

    @Test
    void an_expired_window_cannot_be_paid() {
        props.setIntentTtl(Duration.ZERO);
        PaymentIntent i = newIntent();

        assertThat(checkout.page(i.getId(), i.getClientSecret()).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(decide(i, "approve", CheckoutController.OTP).getStatusCode()).isEqualTo(HttpStatus.GONE);
        verifyNoInteractions(webhooks);
    }

    @Test
    void a_wrong_otp_shows_the_form_again_with_an_error() {
        PaymentIntent i = newIntent();

        String page = decide(i, "approve", "000000").getBody();

        assertThat(page).contains("didn&#39;t match").contains("name=\"otp\"");
    }

    @Test
    void the_result_page_tells_the_merchant_app_when_framed() {
        PaymentIntent i = newIntent();

        String page = decide(i, "approve", CheckoutController.OTP).getBody();

        assertThat(page).contains("postMessage").contains("'payment-sim'").contains(i.getId()).contains("AUTHORIZED");
    }
}
