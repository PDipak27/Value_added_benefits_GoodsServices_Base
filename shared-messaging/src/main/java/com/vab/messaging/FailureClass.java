package com.vab.messaging;

/** How {@link ResilientMessageHandlerDecorator} treats a failed message (Design/13 §3). */
public enum FailureClass {

    /** Retrying fixes it: lock conflicts, deadlocks, serialization failures, an outbox-id clash. Bounded retry, then park. */
    CONTENTION,

    /** Our own infrastructure (DB, Mongo, connection pool) is unavailable. Wait without limit; never park. */
    INFRA_DOWN,

    /** Anything else: bad payload, missing handler, bug. A few retries (cheap insurance), then park. */
    POISON
}
