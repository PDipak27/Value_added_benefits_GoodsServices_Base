package com.vab.order.command.api;

import com.vab.order.api.GlobalExceptionHandler;
import com.vab.order.command.domain.Order;
import com.vab.order.command.domain.OrderRepository;
import com.vab.order.command.domain.PlaceOrderCommand;
import com.vab.order.command.service.OrderCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Command API (§A-3/A5, DD-26/27): subscriberId comes from the JWT, Idempotency-Key is mandatory,
 * cancel is owner-only with 404 for non-owners (admin may act on any), and wrong-state actions map to 409.
 * The role gate itself (vab-admin on back-office POSTs) is in {@code SecurityConfig} → see {@code OrderSecurityIT}.
 */
@ExtendWith(MockitoExtension.class)
class OrderCommandControllerTest {

    private static final String IDEM = "3f1c2b7e-9a4d-4c1e-8b6a-1d2e3f4a5b6c";
    private static final String PLACE_BODY = """
            {"offerCode":"OTT_NETFLIX_6M","productType":"DIGITAL_SUBSCRIPTION","priceSnapshotId":"px-1",
             "amount":499,"currency":"INR","billingMode":"PAY_NOW","subscriberId":"sub-attacker"}""";

    @Mock OrderCommandService commandService;
    @Mock OrderRepository orderRepo;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new OrderCommandController(commandService, orderRepo))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static void login(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static void loginAs(String subscriberId) {
        login(Jwt.withTokenValue("t").header("alg", "none").claim("subscriberId", subscriberId).build());
    }

    private static void loginAsAdmin() {
        login(Jwt.withTokenValue("t").header("alg", "none").subject("ops-admin")
                .claim("realm_access", Map.of("roles", List.of("vab-admin"))).build());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Order orderOwnedBy(String orderId, String subscriberId) {
        return Order.place(orderId, subscriberId, "OTT_NETFLIX_6M", "DIGITAL_SUBSCRIPTION", "px-1", 499, "INR", "PAY_NOW");
    }

    // ── POST /v1/orders ─────────────────────────────────────────────────────

    /** subscriberId is taken from the token claim — a subscriberId in the body is ignored — and the request is mapped 1:1 into the command. */
    @Test
    void place_uses_subscriber_from_token_not_body() throws Exception {
        loginAs("sub-premium");
        when(commandService.placeOrder(any())).thenReturn("ord-1");

        mvc().perform(post("/v1/orders").header("Idempotency-Key", IDEM)
                        .contentType(MediaType.APPLICATION_JSON).content(PLACE_BODY))
                .andExpect(status().isAccepted());

        ArgumentCaptor<PlaceOrderCommand> cmd = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(commandService).placeOrder(cmd.capture());
        assertThat(cmd.getValue().getSubscriberId()).isEqualTo("sub-premium");
        assertThat(cmd.getValue().getIdempotencyKey()).isEqualTo(IDEM);
        assertThat(cmd.getValue()).extracting(PlaceOrderCommand::getOfferCode, PlaceOrderCommand::getProductType,
                        PlaceOrderCommand::getPriceSnapshotId, PlaceOrderCommand::getAmount,
                        PlaceOrderCommand::getCurrency, PlaceOrderCommand::getBillingMode)
                .containsExactly("OTT_NETFLIX_6M", "DIGITAL_SUBSCRIPTION", "px-1", 499L, "INR", "PAY_NOW");
    }

    /** Accepted placement answers 202 with Location /v1/orders/{id} and the orderId in the body. */
    @Test
    void place_returns_202_with_location_and_order_id() throws Exception {
        loginAs("sub-premium");
        when(commandService.placeOrder(any())).thenReturn("ord-1");

        mvc().perform(post("/v1/orders").header("Idempotency-Key", IDEM)
                        .contentType(MediaType.APPLICATION_JSON).content(PLACE_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/v1/orders/ord-1")))
                .andExpect(jsonPath("$.orderId").value("ord-1"));
    }

    /** A missing Idempotency-Key header is a 400 and nothing is placed. */
    @Test
    void place_without_idempotency_key_is_400() throws Exception {
        loginAs("sub-premium");

        mvc().perform(post("/v1/orders").contentType(MediaType.APPLICATION_JSON).content(PLACE_BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(commandService);
    }

    /** A blank or non-UUID Idempotency-Key is a 400 and nothing is placed. */
    @Test
    void place_with_invalid_idempotency_key_is_400() throws Exception {
        loginAs("sub-premium");

        for (String bad : List.of(" ", "not-a-uuid")) {
            mvc().perform(post("/v1/orders").header("Idempotency-Key", bad)
                            .contentType(MediaType.APPLICATION_JSON).content(PLACE_BODY))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(commandService);
    }

    /** A valid token with no subscriberId claim (e.g. a service/admin token) cannot place an order: 403. */
    @Test
    void place_with_token_lacking_subscriber_claim_is_403() throws Exception {
        loginAsAdmin();

        mvc().perform(post("/v1/orders").header("Idempotency-Key", IDEM)
                        .contentType(MediaType.APPLICATION_JSON).content(PLACE_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(commandService);
    }

    /** A business conflict raised by the service (already entitled to the offer) surfaces as 409. */
    @Test
    void place_already_entitled_is_409() throws Exception {
        loginAs("sub-premium");
        when(commandService.placeOrder(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Already entitled to offer OTT_NETFLIX_6M"));

        mvc().perform(post("/v1/orders").header("Idempotency-Key", IDEM)
                        .contentType(MediaType.APPLICATION_JSON).content(PLACE_BODY))
                .andExpect(status().isConflict());
    }

    // ── POST /v1/orders/{id}/cancel ─────────────────────────────────────────

    /** The owner can cancel: 202 and the cancel flag is requested on the service. */
    @Test
    void owner_cancel_is_202() throws Exception {
        loginAs("sub-premium");
        when(orderRepo.findById("ord-1")).thenReturn(Optional.of(orderOwnedBy("ord-1", "sub-premium")));

        mvc().perform(post("/v1/orders/ord-1/cancel")).andExpect(status().isAccepted());
        verify(commandService).requestCancel("ord-1");
    }

    /** Another subscriber's order is reported as 404 (not 403), so its existence isn't leaked; nothing is cancelled. */
    @Test
    void non_owner_cancel_is_404_not_403() throws Exception {
        loginAs("sub-other");
        when(orderRepo.findById("ord-1")).thenReturn(Optional.of(orderOwnedBy("ord-1", "sub-premium")));

        mvc().perform(post("/v1/orders/ord-1/cancel")).andExpect(status().isNotFound());
        verify(commandService, never()).requestCancel(anyString());
    }

    /** An unknown order id gives the same 404 as a non-owned one (indistinguishable to the caller). */
    @Test
    void cancel_unknown_order_is_404() throws Exception {
        loginAs("sub-premium");
        when(orderRepo.findById("ord-x")).thenReturn(Optional.empty());

        mvc().perform(post("/v1/orders/ord-x/cancel")).andExpect(status().isNotFound());
        verify(commandService, never()).requestCancel(anyString());
    }

    /** An admin (vab-admin realm role) may cancel any subscriber's order, without a subscriberId claim and without an ownership lookup. */
    @Test
    void admin_can_cancel_any_order() throws Exception {
        loginAsAdmin();

        mvc().perform(post("/v1/orders/ord-1/cancel")).andExpect(status().isAccepted());
        verify(commandService).requestCancel("ord-1");
        verifyNoInteractions(orderRepo);
    }

    /** Cancelling a terminal order (e.g. COMPLETED) is refused with 409. */
    @Test
    void cancel_terminal_order_is_409() throws Exception {
        loginAs("sub-premium");
        when(orderRepo.findById("ord-1")).thenReturn(Optional.of(orderOwnedBy("ord-1", "sub-premium")));
        doThrow(new IllegalStateException("Order ord-1 is COMPLETED")).when(commandService).requestCancel("ord-1");

        mvc().perform(post("/v1/orders/ord-1/cancel")).andExpect(status().isConflict());
    }

    // ── Back-office: retry / complete / revoke (DD-27, Phase 3) ─────────────

    /** Retry on a parked order re-drives fulfilment: 202. */
    @Test
    void retry_fulfilment_is_202() throws Exception {
        loginAsAdmin();

        mvc().perform(post("/v1/orders/ord-1/retry-fulfilment")).andExpect(status().isAccepted());
        verify(commandService).retryFulfilment("ord-1");
    }

    /** Retry on an order that is not FULFILMENT_FAILED is 409. */
    @Test
    void retry_fulfilment_wrong_state_is_409() throws Exception {
        loginAsAdmin();
        doThrow(new IllegalStateException("not parked")).when(commandService).retryFulfilment("ord-1");

        mvc().perform(post("/v1/orders/ord-1/retry-fulfilment")).andExpect(status().isConflict());
    }

    /** Manual complete passes the out-of-band externalRef from the body to the service: 202. */
    @Test
    void complete_fulfilment_passes_external_ref() throws Exception {
        loginAsAdmin();

        mvc().perform(post("/v1/orders/ord-1/complete-fulfilment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalRef\":\"OTT-123\"}"))
                .andExpect(status().isAccepted());
        verify(commandService).completeFulfilment("ord-1", "OTT-123");
    }

    /** Manual complete on an order that is not parked is 409. */
    @Test
    void complete_fulfilment_wrong_state_is_409() throws Exception {
        loginAsAdmin();
        doThrow(new IllegalStateException("not parked")).when(commandService).completeFulfilment("ord-1", "OTT-123");

        mvc().perform(post("/v1/orders/ord-1/complete-fulfilment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalRef\":\"OTT-123\"}"))
                .andExpect(status().isConflict());
    }

    /** Revoke on a COMPLETED order requests the entitlement revoke: 202. */
    @Test
    void revoke_entitlement_is_202() throws Exception {
        loginAsAdmin();

        mvc().perform(post("/v1/orders/ord-1/revoke-entitlement")).andExpect(status().isAccepted());
        verify(commandService).requestEntitlementRevoke("ord-1");
    }

    /** Revoke on an order that is not COMPLETED (or has no entitlement) is 409. */
    @Test
    void revoke_entitlement_wrong_state_is_409() throws Exception {
        loginAsAdmin();
        doThrow(new IllegalStateException("not completed")).when(commandService).requestEntitlementRevoke("ord-1");

        mvc().perform(post("/v1/orders/ord-1/revoke-entitlement")).andExpect(status().isConflict());
    }
}
