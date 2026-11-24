package com.vab.order.ops;

import com.vab.messaging.ParkedMessage;
import com.vab.messaging.ParkedMessageStore;
import io.eventuate.tram.messaging.producer.MessageProducer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** DLQ admin API (Design/13 §7): status codes for each re-drive / discard outcome. */
class ParkedMessageAdminControllerTest {

    private final ParkedMessageStore store = mock(ParkedMessageStore.class);
    private final MessageProducer producer = mock(MessageProducer.class);
    private final ParkedMessageAdminController controller = new ParkedMessageAdminController(store, producer);

    @Test
    void redrive_is_202_with_the_new_message_id() {
        when(store.redrive(7, producer)).thenReturn(Optional.of("msg-new"));

        ResponseEntity<Map<String, String>> r = controller.redrive(7);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r.getBody()).containsEntry("status", "REDRIVEN").containsEntry("messageId", "msg-new");
    }

    @Test
    void redrive_of_an_already_processed_message_is_200_processed() {
        when(store.redrive(7, producer)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> r = controller.redrive(7);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status", "PROCESSED").doesNotContainKey("messageId");
    }

    @Test
    void redrive_of_a_row_that_is_not_parked_is_409() {
        when(store.redrive(7, producer)).thenThrow(new IllegalStateException("Parked message 7 is REDRIVEN, not PARKED"));

        assertThatThrownBy(() -> controller.redrive(7))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void discard_is_204_and_409_when_not_parked() {
        assertThat(controller.discard(7).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        doThrow(new IllegalStateException("not PARKED")).when(store).discard(8);
        assertThatThrownBy(() -> controller.discard(8))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void count_reports_parked_and_unroutable() {
        when(store.count(ParkedMessage.Status.PARKED)).thenReturn(2L);
        when(store.count(ParkedMessage.Status.UNROUTABLE)).thenReturn(1L);

        assertThat(controller.count()).containsEntry("parked", 2L).containsEntry("unroutable", 1L);
    }

    @Test
    void unknown_id_is_404() {
        when(store.find(9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get(9))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
