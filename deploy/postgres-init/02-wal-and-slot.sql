-- ============================================================
-- WAL configuration for Eventuate CDC (Postgres WAL mode).
-- Run as superuser.
-- ============================================================

-- WAL logical replication NOT required when using EventuatePolling mode.
-- This file is intentionally empty for the Windows / Postgres 18 setup.
-- If you switch to PostgresWal mode in future, wal2json or pgoutput is required.

-- 1. Enable logical replication (requires Postgres restart after ALTER SYSTEM)
--ALTER SYSTEM SET wal_level = logical;
-- After running this, restart Postgres, then run the SELECT below.

-- 2. Create the logical replication slot (run AFTER restart)
--SELECT pg_create_logical_replication_slot('eventuate_slot', 'wal2json')
--WHERE NOT EXISTS (
 --   SELECT 1 FROM pg_replication_slots WHERE slot_name = 'eventuate_slot'
--);

-- 3. Verify
--SELECT slot_name, plugin, slot_type, active FROM pg_replication_slots;
