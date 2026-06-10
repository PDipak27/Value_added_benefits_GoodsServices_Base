package com.vab.catalog.config;

/**
 * A stable, unique identifier for <em>this</em> running catalog-service instance
 * (DD-19). Stamped into every cache-invalidation broadcast so a receiver can
 * recognise — and skip — its own messages (the originator already evicted its L1
 * synchronously).
 *
 * <p>Wrapped in a type (rather than a bare {@code String} bean) to avoid
 * by-type {@code String} injection ambiguity.
 */
public final class InstanceId {

    private final String value;

    public InstanceId(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
