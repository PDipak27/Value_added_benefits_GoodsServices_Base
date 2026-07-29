-- V5: non-terminal FULFILMENT_FAILED park for OTT provisioning failures (DD-27).
-- The order's charge stands; an admin re-drives or manually completes. We only
-- need to remember when the last fulfilment attempt ran; the parked status itself
-- lives in the existing `status` column and failedStep/failureReason are reused.

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMPTZ;
