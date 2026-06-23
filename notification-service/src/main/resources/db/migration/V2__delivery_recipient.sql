-- DD-27: persist the resolved recipient so backoffice/admin alerts are auditable
-- (subscriber-facing rows key on the order; ORDER_FULFILMENT_FAILED targets the ops desk).

ALTER TABLE notification.delivery_log ADD COLUMN IF NOT EXISTS recipient VARCHAR(128);
