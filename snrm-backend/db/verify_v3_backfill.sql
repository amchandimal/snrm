-- ============================================================================
-- verify_v3_backfill.sql — read-only checks that V3__time_units.sql preserved
-- the meaning of every quantity it converted (FR-13).
--
-- Run AFTER the application has started once, so Flyway has applied V3:
--
--   PowerShell      : mysql -u snrm_app -p snrm -e "source db/verify_v3_backfill.sql"
--   Git Bash / sh   : mysql -u snrm_app -p snrm < db/verify_v3_backfill.sql
--   MySQL Workbench : open this file, connect, execute the whole script
--
-- PowerShell has no `<` redirection operator, hence the client's own `source`.
--
-- Nothing here writes, except the very last statement, which is commented out.
-- Expected results are stated above each query; unless a query says otherwise,
-- ZERO ROWS is a pass.
--
-- Checks 4-6 read v3_time_units_audit, the table V3 filled in with each old
-- value and what it became. It is the only record of the pre-migration numbers
-- — the columns themselves are gone — so run those checks before dropping it.
--
-- Floating point: every comparison uses a relative tolerance rather than "=".
-- The backfill multiplies and divides doubles, so a converted value can differ
-- from the arithmetically exact one in the last bit or two. A real error is
-- never that small; it is a factor of 24, or 7, or 86400.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. Flyway applied V3 cleanly.
--    Expect three rows, V1 / V2 / V3, all success = 1.
-- ---------------------------------------------------------------------------
SELECT installed_rank, version, description, type, success, installed_on
FROM   snrm.flyway_schema_history
ORDER  BY installed_rank;


-- ---------------------------------------------------------------------------
-- 2. The old columns are gone and the new ones are present.
--    Expect exactly 26 rows (5 network + 5 node + 5 link + 4 node_product
--    + 6 disruption_event + 1 metric_result), all with present = 'yes'.
-- ---------------------------------------------------------------------------
SELECT   table_name, column_name, column_type, is_nullable, column_default,
         'yes' AS present
FROM     information_schema.columns
WHERE    table_schema = 'snrm'
  AND    (   (table_name = 'network'          AND column_name IN ('period_length_value','period_length_unit','period_length_seconds','horizon_periods','rounding_policy'))
          OR (table_name = 'node'             AND column_name IN ('capacity_value','capacity_time_unit','processing_time_value','processing_time_unit','processing_time_seconds'))
          OR (table_name = 'link'             AND column_name IN ('lead_time_value','lead_time_unit','lead_time_seconds','capacity_value','capacity_time_unit'))
          OR (table_name = 'node_product'     AND column_name IN ('demand_value','demand_time_unit','holding_cost_value','holding_cost_time_unit'))
          OR (table_name = 'disruption_event' AND column_name IN ('start_offset_value','start_offset_unit','start_offset_seconds','duration_value','duration_unit','duration_seconds'))
          OR (table_name = 'metric_result'    AND column_name = 'display_unit'))
ORDER BY table_name, column_name;

--    The superseded columns. Expect ZERO ROWS.
SELECT   table_name, column_name, 'STILL PRESENT — the drop did not run' AS problem
FROM     information_schema.columns
WHERE    table_schema = 'snrm'
  AND    (   (table_name = 'node'             AND column_name = 'capacity_per_period')
          OR (table_name = 'link'             AND column_name IN ('lead_time','capacity_per_period'))
          OR (table_name = 'node_product'     AND column_name IN ('demand_per_period','holding_cost'))
          OR (table_name = 'disruption_event' AND column_name IN ('start_period','duration')));


-- ---------------------------------------------------------------------------
-- 3. Every duration is internally consistent: seconds = value x unit.
--
--    This is the check that matters longest. It needs no audit table and stays
--    valid forever, so it is worth re-running any time the derived columns are
--    suspected of drifting — a row edited by hand, or an entity that embeds
--    DurationAmount without the @PrePersist/@PreUpdate callback.
--
--    Expect ZERO ROWS. One query over all five duration columns in the schema.
-- ---------------------------------------------------------------------------
SELECT   quantity, row_key, value, unit, stored_seconds, expected_seconds
FROM (
    SELECT 'network.period_length' AS quantity, id AS row_key,
           period_length_value AS value, period_length_unit AS unit,
           period_length_seconds AS stored_seconds,
           period_length_value * CASE period_length_unit
               WHEN 'SECOND' THEN 1 WHEN 'MINUTE' THEN 60 WHEN 'HOUR' THEN 3600
               WHEN 'DAY' THEN 86400 WHEN 'WEEK' THEN 604800
               WHEN 'MONTH' THEN 2592000 WHEN 'YEAR' THEN 31536000 END AS expected_seconds
    FROM   snrm.network
    UNION ALL
    SELECT 'node.processing_time', id,
           processing_time_value, processing_time_unit, processing_time_seconds,
           processing_time_value * CASE processing_time_unit
               WHEN 'SECOND' THEN 1 WHEN 'MINUTE' THEN 60 WHEN 'HOUR' THEN 3600
               WHEN 'DAY' THEN 86400 WHEN 'WEEK' THEN 604800
               WHEN 'MONTH' THEN 2592000 WHEN 'YEAR' THEN 31536000 END
    FROM   snrm.node
    UNION ALL
    SELECT 'link.lead_time', id,
           lead_time_value, lead_time_unit, lead_time_seconds,
           lead_time_value * CASE lead_time_unit
               WHEN 'SECOND' THEN 1 WHEN 'MINUTE' THEN 60 WHEN 'HOUR' THEN 3600
               WHEN 'DAY' THEN 86400 WHEN 'WEEK' THEN 604800
               WHEN 'MONTH' THEN 2592000 WHEN 'YEAR' THEN 31536000 END
    FROM   snrm.link
    UNION ALL
    SELECT 'disruption_event.start_offset', id,
           start_offset_value, start_offset_unit, start_offset_seconds,
           start_offset_value * CASE start_offset_unit
               WHEN 'SECOND' THEN 1 WHEN 'MINUTE' THEN 60 WHEN 'HOUR' THEN 3600
               WHEN 'DAY' THEN 86400 WHEN 'WEEK' THEN 604800
               WHEN 'MONTH' THEN 2592000 WHEN 'YEAR' THEN 31536000 END
    FROM   snrm.disruption_event
    UNION ALL
    SELECT 'disruption_event.duration', id,
           duration_value, duration_unit, duration_seconds,
           duration_value * CASE duration_unit
               WHEN 'SECOND' THEN 1 WHEN 'MINUTE' THEN 60 WHEN 'HOUR' THEN 3600
               WHEN 'DAY' THEN 86400 WHEN 'WEEK' THEN 604800
               WHEN 'MONTH' THEN 2592000 WHEN 'YEAR' THEN 31536000 END
    FROM   snrm.disruption_event
) d
WHERE  ABS(stored_seconds - expected_seconds) > 1e-6 * GREATEST(1, ABS(expected_seconds));


-- ---------------------------------------------------------------------------
-- 4. THE BACKFILL PRESERVED MEANING — durations.
--
--    A duration of n periods had to become n x period_seconds of real time,
--    and n x period_value of the period's unit. Both are checked.
--
--    Expect ZERO ROWS.
-- ---------------------------------------------------------------------------
SELECT quantity, row_key, old_value AS old_periods, period_seconds,
       new_value, new_unit, new_seconds,
       old_value * period_seconds AS expected_seconds,
       old_value * period_value   AS expected_value
FROM   snrm.v3_time_units_audit
WHERE  new_seconds IS NOT NULL           -- durations only; rates have no derived column
  AND  (   ABS(new_seconds - old_value * period_seconds)
             > 1e-6 * GREATEST(1, ABS(old_value * period_seconds))
        OR ABS(new_value   - old_value * period_value)
             > 1e-6 * GREATEST(1, ABS(old_value * period_value)));


-- ---------------------------------------------------------------------------
-- 5. THE BACKFILL PRESERVED MEANING — rates.
--
--    A rate of x per period had to become the same throughput restated per
--    unit. Reading it back the other way, the new value multiplied by the
--    number of the new unit's seconds in one period must give the old
--    per-period figure:
--
--        new_value x (period_seconds / period_value) = old_value
--
--    (period_seconds / period_value is secondsOf(new_unit), since the backfill
--    set new_unit = period_length_unit.)
--
--    A NULL old_value meant "unconstrained" and must still be NULL — a rate
--    that acquired a number, or lost one, is a changed model. That is the
--    second half of the WHERE.
--
--    Expect ZERO ROWS.
-- ---------------------------------------------------------------------------
SELECT quantity, row_key, old_value AS old_per_period, period_value, period_seconds,
       new_value, new_unit,
       new_value * (period_seconds / period_value) AS restated_per_period
FROM   snrm.v3_time_units_audit
WHERE  new_seconds IS NULL               -- rates only
  AND  (   (old_value IS NULL) <> (new_value IS NULL)
        OR (old_value IS NOT NULL
            AND ABS(new_value * (period_seconds / period_value) - old_value)
                  > 1e-6 * GREATEST(1, ABS(old_value))));


-- ---------------------------------------------------------------------------
-- 6. Nothing was missed and nothing was invented.
--
--    The audit table must hold exactly one row per converted quantity per row
--    of the source table. Expect every row to read 'match'.
-- ---------------------------------------------------------------------------
SELECT   a.quantity,
         a.audited,
         s.actual,
         IF(a.audited = s.actual, 'match', 'MISMATCH') AS verdict
FROM     (SELECT quantity, COUNT(*) AS audited
          FROM   snrm.v3_time_units_audit GROUP BY quantity) a
JOIN     (SELECT 'node.capacity' AS quantity, COUNT(*) AS actual FROM snrm.node
          UNION ALL SELECT 'link.lead_time', COUNT(*) FROM snrm.link
          UNION ALL SELECT 'link.capacity',  COUNT(*) FROM snrm.link
          UNION ALL SELECT 'node_product.demand',       COUNT(*) FROM snrm.node_product
          UNION ALL SELECT 'node_product.holding_cost', COUNT(*) FROM snrm.node_product
          UNION ALL SELECT 'disruption_event.start_offset', COUNT(*) FROM snrm.disruption_event
          UNION ALL SELECT 'disruption_event.duration',     COUNT(*) FROM snrm.disruption_event) s
      ON s.quantity = a.quantity
ORDER BY a.quantity;


-- ---------------------------------------------------------------------------
-- 7. The index exists, on the derived second-counts.
--    Expect three rows: scenario_id, start_offset_seconds, duration_seconds,
--    in that sequence. ix_event_scenario must NOT appear.
-- ---------------------------------------------------------------------------
SELECT   index_name, seq_in_index, column_name
FROM     information_schema.statistics
WHERE    table_schema = 'snrm' AND table_name = 'disruption_event'
ORDER BY index_name, seq_in_index;


-- ---------------------------------------------------------------------------
-- 8. Eyeball it. Nothing above catches a rule that is self-consistently wrong,
--    so read a sample and check it says what you expect: a network you know to
--    have been modelled in weeks should not now claim its links take days.
--
--    The 'reads as' column is the sentence the old number used to mean.
-- ---------------------------------------------------------------------------
SELECT   a.quantity, a.row_key, a.old_value, a.new_value, a.new_unit,
         CONCAT(a.old_value, ' per period of ', a.period_value, ' x ',
                a.period_seconds / a.period_value, 's') AS reads_as
FROM     snrm.v3_time_units_audit a
ORDER BY a.quantity, CAST(a.row_key AS UNSIGNED)
LIMIT    40;

--    And the clock each network ended up with. Expect 1 DAY / 86400 everywhere
--    unless you have since changed one by hand.
SELECT id, name, version, is_baseline,
       period_length_value, period_length_unit, period_length_seconds,
       horizon_periods, rounding_policy
FROM   snrm.network
ORDER  BY project_id, name, version;


-- ---------------------------------------------------------------------------
-- 9. When checks 2-7 have all passed, drop the audit table.
--
--    It is a one-off migration artefact, nothing in the application reads it,
--    and while it exists db/verify_v2_schema.sql check 2 will report 13 tables
--    where it expects 12. Uncomment and run once satisfied — after this the
--    pre-V3 numbers are gone for good, so do not run it on the same day you
--    run the migration.
-- ---------------------------------------------------------------------------
-- DROP TABLE snrm.v3_time_units_audit;
