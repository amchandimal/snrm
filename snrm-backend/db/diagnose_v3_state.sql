-- ============================================================================
-- diagnose_v3_state.sql — how far did V3__time_units.sql get before it failed?
--
-- READ-ONLY. Nothing here writes. Run it, paste the output back.
--
--   PowerShell      : mysql -u snrm_app -p snrm -e "source db/diagnose_v3_state.sql"
--   Git Bash / sh   : mysql -u snrm_app -p snrm < db/diagnose_v3_state.sql
--   MySQL Workbench : open, connect, execute the whole script
--
-- PowerShell has no `<` redirection operator, hence the client's own `source`
-- command. Do not reach for `Get-Content ... | mysql` instead: -p reads the
-- password from stdin, so a piped file would be swallowed by the prompt.
-- Paths in `source` are relative to the shell's working directory — run from
-- the project root.
--
-- MySQL does not roll back DDL, so a migration that fails halfway leaves the
-- schema wherever it stopped. Flyway records the attempt with success = 0 and
-- then refuses to do anything else until the schema is put back to a state its
-- history agrees with. This script establishes what that state actually is;
-- db/repair_v3_teardown.sql then puts it back.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. Server version.
--    CHECK constraints are only enforced from 8.0.16, and ALTER TABLE ... DROP
--    CHECK only exists from 8.0.16. On anything older, V2's CHECK constraints
--    were parsed and silently ignored, so V3's attempt to drop them by name
--    would fail with "check constraint does not exist" — a different fault from
--    the foreign-key one, with a different fix.
-- ---------------------------------------------------------------------------
SELECT VERSION() AS mysql_version, DATABASE() AS current_schema;


-- ---------------------------------------------------------------------------
-- 2. What Flyway thinks happened.
--    Expect V1 and V2 with success = 1, and V3 with success = 0.
-- ---------------------------------------------------------------------------
SELECT installed_rank, version, description, type, success, installed_on,
       execution_time
FROM   flyway_schema_history
ORDER  BY installed_rank;


-- ---------------------------------------------------------------------------
-- 3. Which V3 columns landed.
--    Sections 1-6 of the migration add 26 columns. Anything less than 26 means
--    it failed before the additive work finished.
-- ---------------------------------------------------------------------------
SELECT   table_name, column_name, column_type, is_nullable, column_default
FROM     information_schema.columns
WHERE    table_schema = DATABASE()
  AND    (   (table_name = 'network'          AND column_name IN ('period_length_value','period_length_unit','period_length_seconds','horizon_periods','rounding_policy'))
          OR (table_name = 'node'             AND column_name IN ('capacity_value','capacity_time_unit','processing_time_value','processing_time_unit','processing_time_seconds'))
          OR (table_name = 'link'             AND column_name IN ('lead_time_value','lead_time_unit','lead_time_seconds','capacity_value','capacity_time_unit'))
          OR (table_name = 'node_product'     AND column_name IN ('demand_value','demand_time_unit','holding_cost_value','holding_cost_time_unit'))
          OR (table_name = 'disruption_event' AND column_name IN ('start_offset_value','start_offset_unit','start_offset_seconds','duration_value','duration_unit','duration_seconds'))
          OR (table_name = 'metric_result'    AND column_name = 'display_unit'))
ORDER BY table_name, column_name;

SELECT COUNT(*) AS v3_columns_present, 26 AS expected
FROM     information_schema.columns
WHERE    table_schema = DATABASE()
  AND    (   (table_name = 'network'          AND column_name IN ('period_length_value','period_length_unit','period_length_seconds','horizon_periods','rounding_policy'))
          OR (table_name = 'node'             AND column_name IN ('capacity_value','capacity_time_unit','processing_time_value','processing_time_unit','processing_time_seconds'))
          OR (table_name = 'link'             AND column_name IN ('lead_time_value','lead_time_unit','lead_time_seconds','capacity_value','capacity_time_unit'))
          OR (table_name = 'node_product'     AND column_name IN ('demand_value','demand_time_unit','holding_cost_value','holding_cost_time_unit'))
          OR (table_name = 'disruption_event' AND column_name IN ('start_offset_value','start_offset_unit','start_offset_seconds','duration_value','duration_unit','duration_seconds'))
          OR (table_name = 'metric_result'    AND column_name = 'display_unit'));


-- ---------------------------------------------------------------------------
-- 4. THE IMPORTANT ONE — are the pre-V3 columns still there?
--
--    Expect 7 rows. If all 7 are present, no original data has been destroyed
--    and the teardown is completely lossless. If any are missing, section 9 of
--    the migration ran and those numbers now exist only in v3_time_units_audit
--    (check 6 below), which is what the teardown would have to restore them
--    from — say so when you paste this back.
-- ---------------------------------------------------------------------------
SELECT   table_name, column_name, column_type
FROM     information_schema.columns
WHERE    table_schema = DATABASE()
  AND    (   (table_name = 'node'             AND column_name = 'capacity_per_period')
          OR (table_name = 'link'             AND column_name IN ('lead_time','capacity_per_period'))
          OR (table_name = 'node_product'     AND column_name IN ('demand_per_period','holding_cost'))
          OR (table_name = 'disruption_event' AND column_name IN ('start_period','duration')))
ORDER BY table_name, column_name;


-- ---------------------------------------------------------------------------
-- 5. CHECK constraints, and the disruption_event indexes.
--
--    The suspected failure point is section 8. If the theory is right you will
--    see: the five V2 CHECKs of section 8 gone, ck_event_scenario's index still
--    present, and ix_event_window absent.
-- ---------------------------------------------------------------------------
SELECT   tc.table_name, tc.constraint_name, cc.check_clause
FROM     information_schema.table_constraints tc
JOIN     information_schema.check_constraints cc
      ON cc.constraint_schema = tc.constraint_schema
     AND cc.constraint_name   = tc.constraint_name
WHERE    tc.constraint_schema = DATABASE()
  AND    tc.constraint_type   = 'CHECK'
ORDER BY tc.table_name, tc.constraint_name;

SELECT   index_name, seq_in_index, column_name
FROM     information_schema.statistics
WHERE    table_schema = DATABASE() AND table_name = 'disruption_event'
ORDER BY index_name, seq_in_index;


-- ---------------------------------------------------------------------------
-- 6. The audit table — did section 7 run, and does it hold every row?
--    Zero rows returned means the migration never reached section 7.
-- ---------------------------------------------------------------------------
SELECT   table_name, table_rows
FROM     information_schema.tables
WHERE    table_schema = DATABASE() AND table_name = 'v3_time_units_audit';

SELECT   quantity, COUNT(*) AS rows_recorded,
         SUM(old_value IS NULL) AS old_nulls,
         SUM(new_value IS NULL) AS new_nulls
FROM     v3_time_units_audit
GROUP BY quantity
ORDER BY quantity;


-- ---------------------------------------------------------------------------
-- 7. How much data is at stake, for context.
-- ---------------------------------------------------------------------------
-- `rows` is a reserved word in MySQL 8 — the alias has to be row_count.
SELECT 'network' AS table_name, COUNT(*) AS row_count FROM network
UNION ALL SELECT 'node',             COUNT(*) FROM node
UNION ALL SELECT 'link',             COUNT(*) FROM link
UNION ALL SELECT 'node_product',     COUNT(*) FROM node_product
UNION ALL SELECT 'disruption_event', COUNT(*) FROM disruption_event
UNION ALL SELECT 'metric_result',    COUNT(*) FROM metric_result
UNION ALL SELECT 'simulation_run',   COUNT(*) FROM simulation_run;
