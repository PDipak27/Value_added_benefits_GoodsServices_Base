package com.vab.fulfilment.ott;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vab.fulfilment.provisioning.ProvisioningTaskService;
import com.vab.fulfilment.provisioning.ProvisioningTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * OTT's partner webhook (Design/06 §8). Not behind any login: OTT signs each event with the secret we share,
 * so the HMAC over the RAW body is the authentication. Then: timestamp within the replay window, dedupe on
 * the event id, apply. 2xx only once applied; anything else makes OTT retry.
 */
@RestController
public class OttWebhookController {

    static final String SIGNATURE_HEADER = "Ott-Signature";

    private static final Logger log = LoggerFactory.getLogger(OttWebhookController.class);

    private final ProvisioningTaskService provisioning;
    private final ProvisioningTaskStore tasks;
    private final ObjectMapper json;
    private final String secret;
    private final Duration tolerance;

    public OttWebhookController(ProvisioningTaskService provisioning, ProvisioningTaskStore tasks, ObjectMapper json,
                                @Value("${ott.webhook-secret:whsec_ott_test}") String secret,
                                @Value("${ott.signature-tolerance:5m}") Duration tolerance) {
        this.provisioning = provisioning;
        this.tasks        = tasks;
        this.json         = json;
        this.secret       = secret;
        this.tolerance    = tolerance;
    }

    @PostMapping("/webhooks/ott")
    @Transactional
    public ResponseEntity<Void> receive(@RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                        @RequestBody byte[] body) {
        Long timestamp = verify(signature, body);
        if (timestamp == null) {
            log.warn("OTT webhook REJECTED: bad or missing signature");
            return ResponseEntity.status(401).build();
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > tolerance.toSeconds()) {
            log.warn("OTT webhook REJECTED: timestamp outside the replay window");
            return ResponseEntity.badRequest().build();
        }
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
        String eventId = event.path("id").asText(null);
        String type    = event.path("type").asText(null);
        JsonNode data  = event.path("data");
        String orderId = data.path("orderId").asText(null);
        if (eventId == null || type == null || orderId == null) {
            return ResponseEntity.badRequest().build();
        }
        if (!tasks.recordWebhookEvent(eventId, type)) {
            log.info("Duplicate OTT webhook ignored: eventId={}", eventId);
            return ResponseEntity.ok().build();
        }
        switch (type) {
            case "entitlement.activated" -> provisioning.onActivated(orderId, data.path("externalRef").asText(null),
                    instant(data.path("validFrom")), instant(data.path("validUntil")));
            case "entitlement.rejected" -> provisioning.onRejected(orderId, data.path("reason").asText(null));
            default -> log.info("OTT webhook type not handled: eventId={}, type={}", eventId, type);
        }
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
        byte[] expected = hmac(t, body);
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

    private byte[] hmac(long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static Instant instant(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : Instant.parse(node.asText());
    }
}
