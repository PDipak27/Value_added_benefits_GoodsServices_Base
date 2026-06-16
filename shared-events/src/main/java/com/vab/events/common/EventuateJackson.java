package com.vab.events.common;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.eventuate.common.json.mapper.JSonMapper;

/**
 * Eventuate Tram serializes command replies and domain events with its own
 * {@link JSonMapper#objectMapper}, which registers Int128/Jdk8 modules but
 * <em>not</em> JavaTimeModule — so any event field of type {@code java.time.Instant}
 * fails to (de)serialize. We register it here once. {@code Instant} is the
 * timestamp convention across the events (e.g. {@code reservedUntil},
 * {@code confirmedAt}), so this keeps that convention working over the wire.
 *
 * <p>Events carrying an {@code Instant} touch {@link #register()} from a static
 * block so the module is registered before they are ever serialized — in
 * production and in pure-JUnit handler tests alike (no Spring context needed).
 */
public final class EventuateJackson {

    static { JSonMapper.objectMapper.registerModule(new JavaTimeModule()); }

    private EventuateJackson() {}

    /** No-op whose only purpose is to trigger this class's static initializer. */
    public static void register() {}
}
