package com.vab.billing.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The payment provider's webhook (Design/15). Not behind any login: the provider can't get our tokens, so
 * the HMAC signature is the authentication. Checks, in order: signature over the RAW body (re-serialised
 * JSON would not match), timestamp within the replay window, then dedupe on the event id.
 * Answers 2xx only once the event is durably applied; anything else makes the provider retry.
 */
@RestController
public class PspWebhookController {

    static final String SIGNATURE_HEADER = "Psp-Signature";

    private static final Logger log = LoggerFactory.getLogger(PspWebhookController.class);

    private final PaymentService payments;
    private final PaymentProperties props;
    private final ObjectMapper json;

    public PspWebhookController(PaymentService payments, PaymentProperties props, ObjectMapper json) {
        this.payments = payments;
        this.props    = props;
        this.json     = json;
    }

    @PostMapping("/webhooks/psp")
    public ResponseEntity<Void> receive(@RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                        @RequestBody byte[] body) {
        Long timestamp = verify(signature, body);
        if (timestamp == null) {
            log.warn("Webhook REJECTED: bad or missing signature");
            return ResponseEntity.status(401).build();
        }
        long age = Math.abs(Instant.now().getEpochSecond() - timestamp);
        if (age > props.getSignatureTolerance().toSeconds()) {
            log.warn("Webhook REJECTED: timestamp {}s old, outside the replay window", age);
            return ResponseEntity.badRequest().build();
        }
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
        String eventId  = event.path("id").asText(null);
        String type     = event.path("type").asText(null);
        String intentId = event.path("data").path("intentId").asText(null);
        if (eventId == null || type == null || intentId == null) {
            return ResponseEntity.badRequest().build();
        }
        payments.onWebhook(eventId, type, intentId, event.path("data").path("failureReason").asText(null));
        return ResponseEntity.ok().build();
    }

    /** @return the signed timestamp, or null if the header is missing/malformed or no {@code v1} matches */
    Long verify(String header, byte[] body) {
        if (header == null) return null;
        Long t = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) {
                try { t = Long.parseLong(kv[1]); } catch (NumberFormatException e) { return null; }
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (t == null || signatures.isEmpty()) return null;
        byte[] expected = hmac(props.getWebhookSecret(), t, body);
        // Several v1 values are allowed so the secret can be rotated without dropping webhooks.
        for (String sig : signatures) {
            try {
                if (MessageDigest.isEqual(expected, HexFormat.of().parseHex(sig))) return t;
            } catch (IllegalArgumentException ignored) {
                // not hex: try the next one
            }
        }
        return null;
    }

    private static byte[] hmac(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
