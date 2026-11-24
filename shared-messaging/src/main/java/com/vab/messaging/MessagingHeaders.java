package com.vab.messaging;

/** Message headers owned by VA-BAGS messaging. */
public final class MessagingHeaders {

    /**
     * Set on a re-driven parked message: only this subscriber processes it. Every other subscriber of the
     * same channel (for example the other consumers of the {@code Order} event topic) acks and skips it.
     */
    public static final String REDRIVE_TARGET = "vab-redrive-target";

    /** Id of the {@code parked_message} row a re-driven message came from (for tracing in logs). */
    public static final String REDRIVEN_FROM = "vab-redriven-from";

    private MessagingHeaders() {}
}
