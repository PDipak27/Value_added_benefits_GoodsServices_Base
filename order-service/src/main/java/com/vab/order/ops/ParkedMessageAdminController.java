package com.vab.order.ops;

import com.vab.messaging.ParkedMessage;
import com.vab.messaging.ParkedMessageStore;
import io.eventuate.tram.messaging.producer.MessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * DLQ admin (Design/13 §7), back-office only ({@code /v1/ops/**} needs {@code vab-admin}, see SecurityConfig).
 * One central API for every service's parked messages: they all share {@code eventuate.parked_message}, and a
 * re-drive is just an outbox insert to the message's original channel, addressed to the subscriber that
 * parked it.
 */
@RestController
@RequestMapping("/v1/ops/messages/parked")
public class ParkedMessageAdminController {

    private static final Logger log = LoggerFactory.getLogger(ParkedMessageAdminController.class);

    private final ParkedMessageStore store;
    private final MessageProducer messageProducer;

    public ParkedMessageAdminController(ParkedMessageStore store, MessageProducer messageProducer) {
        this.store           = store;
        this.messageProducer = messageProducer;
    }

    /** GET /v1/ops/messages/parked?status=PARKED&limit=50 */
    @GetMapping
    public List<ParkedMessage> list(@RequestParam(defaultValue = "PARKED") ParkedMessage.Status status,
                                    @RequestParam(defaultValue = "50") int limit) {
        return store.list(status, limit);
    }

    /** GET /v1/ops/messages/parked/count → {"parked": n, "unroutable": m}: both still need an admin decision. */
    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("parked", store.count(ParkedMessage.Status.PARKED),
                      "unroutable", store.count(ParkedMessage.Status.UNROUTABLE));
    }

    @GetMapping("/{id}")
    public ParkedMessage get(@PathVariable long id) {
        return store.find(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Parked message not found: " + id));
    }

    /**
     * POST /v1/ops/messages/parked/{id}/redrive → 202 {"status": "REDRIVEN", "messageId": "<new id>"},
     * or 200 {"status": "PROCESSED"} if the original was processed after parking; 409 unless PARKED
     * (an UNROUTABLE row has no channel to send to: discard it).
     */
    @PostMapping("/{id}/redrive")
    public ResponseEntity<Map<String, String>> redrive(@PathVariable long id) {
        try {
            return store.redrive(id, messageProducer)
                    .map(newId -> {
                        log.info("Admin re-drive of parked message {} → {}", id, newId);
                        return ResponseEntity.accepted().body(Map.of("status", "REDRIVEN", "messageId", newId));
                    })
                    .orElseGet(() -> ResponseEntity.ok(Map.of("status", "PROCESSED")));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** POST /v1/ops/messages/parked/{id}/discard → 204; 409 unless PARKED or UNROUTABLE. */
    @PostMapping("/{id}/discard")
    public ResponseEntity<Void> discard(@PathVariable long id) {
        try {
            store.discard(id);
            log.info("Admin discarded parked message {}", id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
