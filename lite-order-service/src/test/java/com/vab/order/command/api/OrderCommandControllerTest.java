package com.vab.order.command.api;

import com.vab.order.command.domain.PlaceOrderCommand;
import com.vab.order.command.service.OrderCommandService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LITE order command API behaviour: Idempotency-Key validation, request → command
 * mapping, the 202 + Location contract, and cancel's 409-on-terminal mapping.
 *
 * <p>Plain unit tests over the controller methods with a mocked service — Spring's
 * header binding / routing is not under test here, so header defaulting (which MVC
 * does, not the method) is exercised by the IT / e2e instead.
 */
@ExtendWith(MockitoExtension.class)
class OrderCommandControllerTest {

    @Mock OrderCommandService commandService;

    private OrderCommandController controller;

    private OrderCommandController controller() {
        return new OrderCommandController(commandService);
    }

    private static OrderCommandController.PlaceOrderRequest request() {
        return new OrderCommandController.PlaceOrderRequest(
                "OFF-1", "PHYSICAL_GOOD", "px-1", 500, "INR", "PAY_NOW");
    }

    private static UriComponentsBuilder ucb() {
        return UriComponentsBuilder.fromUriString("http://localhost");
    }

    @Nested
    class PlaceOrder {

        @Test
        void valid_key_returns_202_with_location_and_body() {
            controller = controller();
            String key = UUID.randomUUID().toString();
            when(commandService.placeOrder(org.mockito.ArgumentMatchers.any())).thenReturn("ord_abc");

            ResponseEntity<OrderCommandController.PlaceOrderResponse> resp =
                    controller.placeOrder(key, "sub-1", request(), ucb());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(resp.getBody().orderId()).isEqualTo("ord_abc");
            assertThat(resp.getHeaders().getLocation().toString()).isEqualTo("http://localhost/v1/orders/ord_abc");
        }

        @Test
        void maps_request_and_subscriber_into_the_command() {
            controller = controller();
            String key = UUID.randomUUID().toString();
            when(commandService.placeOrder(org.mockito.ArgumentMatchers.any())).thenReturn("ord_abc");

            controller.placeOrder(key, "sub-1", request(), ucb());

            ArgumentCaptor<PlaceOrderCommand> cmd = ArgumentCaptor.forClass(PlaceOrderCommand.class);
            verify(commandService).placeOrder(cmd.capture());
            PlaceOrderCommand c = cmd.getValue();
            assertThat(c.getSubscriberId()).isEqualTo("sub-1");
            assertThat(c.getOfferCode()).isEqualTo("OFF-1");
            assertThat(c.getProductType()).isEqualTo("PHYSICAL_GOOD");
            assertThat(c.getAmount()).isEqualTo(500);
            assertThat(c.getBillingMode()).isEqualTo("PAY_NOW");
            assertThat(c.getIdempotencyKey()).isEqualTo(key);
        }

        @Test
        void null_key_is_rejected_400_and_service_untouched() {
            controller = controller();
            assertThatThrownBy(() -> controller.placeOrder(null, "sub-1", request(), ucb()))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting("statusCode").isEqualTo(HttpStatus.BAD_REQUEST);
            org.mockito.Mockito.verifyNoInteractions(commandService);
        }

        @Test
        void blank_key_is_rejected_400() {
            controller = controller();
            assertThatThrownBy(() -> controller.placeOrder("   ", "sub-1", request(), ucb()))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting("statusCode").isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void non_uuid_key_is_rejected_400() {
            controller = controller();
            assertThatThrownBy(() -> controller.placeOrder("not-a-uuid", "sub-1", request(), ucb()))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting("statusCode").isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    class CancelOrder {

        @Test
        void accepts_cancel_and_returns_202() {
            controller = controller();

            ResponseEntity<Void> resp = controller.cancelOrder("ord-1");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            verify(commandService).requestCancel("ord-1");
        }

        @Test
        void terminal_order_maps_illegal_state_to_409() {
            controller = controller();
            doThrow(new IllegalStateException("Order ord-1 is COMPLETED and can no longer be cancelled"))
                    .when(commandService).requestCancel("ord-1");

            assertThatThrownBy(() -> controller.cancelOrder("ord-1"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting("statusCode").isEqualTo(HttpStatus.CONFLICT);
        }
    }
}
