-- V4: user-initiated cancel + forward-recovery (DD-26). Capture is now the pivot;
-- a cancel before it rolls back (CANCELLED), one after it forward-recovers
-- (refund/reverse + release → CANCELLED_REFUNDED). The CAPTURE_FAILED state is
-- retired — a capture decline now rolls back to FAILED.

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS cancelled_at      TIMESTAMPTZ;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS cancel_requested  BOOLEAN NOT NULL DEFAULT FALSE;
