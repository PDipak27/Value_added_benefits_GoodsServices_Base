package com.vab.paymentsim;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory intents, plus the {@code Idempotency-Key → intent} map that makes create retry-safe. */
@Component
public class IntentStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, PaymentIntent> byId = new ConcurrentHashMap<>();
    private final Map<String, String> byIdempotencyKey = new ConcurrentHashMap<>();
    private final PspProperties props;

    public IntentStore(PspProperties props) {
        this.props = props;
    }

    /** Same key → the same intent, even if the first response was lost. */
    public PaymentIntent create(String idempotencyKey, String orderId, long amount, String currency) {
        String id = byIdempotencyKey.computeIfAbsent(idempotencyKey, k -> {
            Instant now = Instant.now();
            PaymentIntent intent = new PaymentIntent("pi_" + token(12), "cs_" + token(16), orderId, amount, currency,
                    now, now.plus(props.getIntentTtl()));
            byId.put(intent.getId(), intent);
            return intent.getId();
        });
        return byId.get(id);
    }

    public Optional<PaymentIntent> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    static String token(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
