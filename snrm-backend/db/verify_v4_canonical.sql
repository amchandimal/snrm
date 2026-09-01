-- ============================================================================
-- verify_v4_canonical.sql — read-only checks that V4 brought the schema into
-- its canonical form.
--
--   PowerShell      : mysql -u root -p snrm --table -e "source db/verify_v4_canonical.sql"
--   Git Bash / sh   : mysql -u root -p snrm < db/verify_v4_canonical.sql
--   MySQL Workbench : open, connect, execute the whole script
--
-- Nothing here writes. Unless a check says otherwise, ZERO ROWS is a pass.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. Flyway applied V4.  Expect four rows, all success = 1.
-- ---------------------------------------------------------------------------
SELECT installed_rank, version, description, success
FROM   flyway_schema_history
ORDER  BY installed_rank;


-- ---------------------------------------------------------------------------
-- 2. Every canonical column is BIGINT.
--    Expect 5 rows, all data_type = 'bigint'.
-- ---------------------------------------------------------------------------
SELECT   table_name, column_name, data_type, is_nullable, column_default
FROM     information_schema.columns
WHERE    table_schema = DATABASE() AND column_name LIKE '%\_seconds'
  AND    table_name IN ('network','node','link','disruption_event')
ORDER BY table_name, column_name;

--    Anything still floating point. Expect ZERO ROWS.
SELECT   table_name, column_name, data_type, 'NOT BIGINT' AS problem
FROM     information_schema.columns
WHERE    table_schema = DATABASE() AND column_name LIKE '%\_seconds'
  AND    table_name IN ('network','node','link','disruption_event')
  AND    data_type <> 'bigint';


-- ---------------------------------------------------------------------------
-- 3. ix_node_proc exists, covering exactly the columns it should.
--    Expect 2 rows: network_id at seq 1, processing_time_seconds at seq 2.
-- ---------------------------------------------------------------------------
SELECT   index_name, seq_in_index, column_name
FROM     information_schema.statistics
WHERE    table_schema = DATABASE() AND table_name = 'node' AND index_name = 'ix_node_proc'
ORDER BY seq_in_index;


-- ---------------------------------------------------------------------------
-- 4. ix_event_window survived the MODIFY of its two trailing columns, and is
--    still there to back fk_event_scenario.
--    Expect 3 rows: scenario_id, start_offset_seconds, duration_seconds.
-- ---------------------------------------------------------------------------
SELECT   index_name, seq_in_index, column_name
FROM     information_schema.statistics
WHERE    table_schema = DATABASE() AND table_name = 'disruption_event'
  AND    index_name = 'ix_event_window'
ORDER BY seq_in_index;


-- ---------------------------------------------------------------------------
-- 5. The CHECK constraints dropped and restored around each MODIFY are back.
--    Expect 4 rows: ck_event_window, ck_link_lead_time, ck_network_period,
--    ck_node_processing_time.
-- ---------------------------------------------------------------------------
SELECT   tc.table_name, tc.constraint_name, cc.check_clause
FROM     information_schema.table_constraints tc
JOIN     information_schema.check_constraints cc
      ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name
WHERE    tc.constraint_schema = DATABASE() AND tc.constraint_type = 'CHECK'
  AND    tc.constraint_name IN ('ck_node_processing_time','ck_link_lead_time',
                                'ck_event_window','ck_network_period')
ORDER BY tc.table_name, tc.constraint_name;


-- ---------------------------------------------------------------------------
-- 6. Every duration is still internally consistent after the type change:
--    seconds = ROUND(value x unit).  Expect ZERO ROWS.
--
--    This is check 3 of verify_v3_backfill.sql restated for an integral
--    canonical column — the comparison is now exact, with no tolerance.
-- ---------------------------------------------------------------------------
SELECT   quantity, row_key, value, unit, stored_seconds, expected_seconds
FROM (
    SELECT 'network.period_length' AS quantity, id AS row_key,
           period_length_value AS value, period_length_unit AS unit,
           period_length_seconds AS stored_seconds,
           ROUND(period_length_value * CASE period_length_unit
               WHEN 'SECOND' THEN 1 WHEN 'MINUTE' THEN 60 WHEN 'HOUR' THEN 3600
               WHEN 'DAY' THEN 86400 WHEN 'WEEK' THEN 604800
               WHEN 'MONTH' THEN 2592000 WHEN 'YEAR' THEN 31536000 END) AS expected_seconds
    FROM   network
    UNION ALL
    SELECT 'node.processing_time', id,
           processing_time_value, processing_time_unit, processing_time_seconds,
           ROUND(processing_time_value * CASE processing_time_unit
               WHEN 'SECOND' THEN 1 WHEN 'MINUTE' THEN 60 WHEN 'HOUR' THEN 3600
               WHEN 'DAY' THEN 86400 WHEN 'WEEK' THEN 604800
               WHEN 'MONTH' THEN 2592000 WHEN 'YEAR' THEN 31536000 END)
    FROM   node
    UNION ALL
    SELECT 'link.lead_time', id,
           lead_time_value, lead_time_unit, lead_time_seconds,
           ROUND(lead_time_value * CASE lead_time_unit
               WHEN 'SECOND' THEN 1 WHEN 'MINUTE' THEN 60 WHEN 'HOUR' THEN 3600
               WHEN 'DAY' THEN 86400 WHEN 'WEEK' THEN 604800
               WHEN 'MONTH' THEN 2592000 WHEN 'YEAR' THEN 31536000 END)
    FROM   link
    UNION ALL
    SELECT 'disruption_event.start_offset', id,
           start_offset_value, start_offset_unit, start_offset_seconds,
           ROUND(start_offset_value * CASE start_offset_unit
               WHEN 'SECOND' THEN 1 WHEN 'MINUTE' THEN 60 WHEN 'HOUR' THEN 3600
               WHEN 'DAY' THEN 86400 WHEN 'WEEK' THEN 604800
               WHEN 'MONTH' THEN 2592000 WHEN 'YEAR' THEN 31536000 END)
    FROM   disruption_event
    UNION ALL
    SELECT 'disruption_event.duration', id,
           duration_value, duration_unit, duration_seconds,
           ROUND(duration_value * CASE duration_unit
               WHEN 'SECOND' THEN 1 WHEN 'MINUTE' THEN 60 WHEN 'HOUR' THEN 3600
               WHEN 'DAY' THEN 86400 WHEN 'WEEK' THEN 604800
               WHEN 'MONTH' THEN 2592000 WHEN 'YEAR' THEN 31536000 END)
    FROM   disruption_event
) d
WHERE  stored_seconds <> expected_seconds;
