-- Design/13 §7: DLQ for Eventuate Tram consumers. Written by shared-messaging's
-- ResilientMessageHandlerDecorator when a message keeps failing (POISON, or CONTENTION
-- past its retry budget). Never written while the DB/infrastructure is down (that waits).
-- Run as the `eventuate` user, after 01-eventuate-schema.sql.

CREATE TABLE IF NOT EXISTS eventuate.parked_message (
  id                  BIGSERIAL    PRIMARY KEY,
  subscriber_id       VARCHAR(255) NOT NULL,
  channel             VARCHAR(255) NOT NULL,
  message_id          VARCHAR(255) NOT NULL,
  headers_json        TEXT         NOT NULL,
  payload             TEXT,
  failure_class       VARCHAR(32)  NOT NULL,          -- CONTENTION | POISON
  error               TEXT,                           -- stack trace, truncated to 8000 chars
  attempts            INT          NOT NULL,
  status              VARCHAR(16)  NOT NULL DEFAULT 'PARKED',   -- PARKED | UNROUTABLE | REDRIVEN | DISCARDED | PROCESSED
  parked_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
  resolved_at         TIMESTAMPTZ,
  redrive_message_id  VARCHAR(255),
  UNIQUE (subscriber_id, message_id)                  -- a redelivered message is never parked twice
);

CREATE INDEX IF NOT EXISTS ix_parked_message_status ON eventuate.parked_message (status, id);
