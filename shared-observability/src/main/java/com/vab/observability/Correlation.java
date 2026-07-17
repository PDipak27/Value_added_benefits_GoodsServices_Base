package com.vab.observability;

/** §C2 B-2: correlation constants shared by the HTTP filter and the Tram interceptor. */
public final class Correlation {

    /** HTTP header carrying the correlation id between edge and services. */
    public static final String HEADER = "X-Correlation-Id";

    /** MDC keys — picked up automatically by the JSON encoder + Loki pattern. */
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_CAUSATION_ID   = "causationId";
    public static final String MDC_MESSAGE_ID     = "messageId";

    private Correlation() {}
}
