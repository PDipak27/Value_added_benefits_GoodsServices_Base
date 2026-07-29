package com.vab.order.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every uncaught exception into a clean, status-only error response (RFC-7807
 * {@link ProblemDetail}). The full stack trace is logged server-side, never put in
 * the body — so a caller can't ingest a trace into its own state/logs. Extends
 * {@link ResponseEntityExceptionHandler} so Spring's own client errors (malformed
 * body, validation, 404, 405) keep their correct 4xx status instead of becoming 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Deliberate outcomes (e.g. business conflict→409): keep the thrown status + reason. */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail onResponseStatus(ResponseStatusException ex) {
        log.warn("Request error: {} {}", ex.getStatusCode(), ex.getReason());
        ProblemDetail pd = ProblemDetail.forStatus(ex.getStatusCode());
        pd.setDetail(ex.getReason());
        return pd;
    }

    /** Persistence faults (SQL/constraint/connection) — transient from the caller's view. */
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail onDataAccess(DataAccessException ex) {
        log.error("Persistence error", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        pd.setDetail("Storage temporarily unavailable");
        return pd;
    }

    /** Anything else — a 500 with no internals in the body. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setDetail("Internal error");
        return pd;
    }
}
