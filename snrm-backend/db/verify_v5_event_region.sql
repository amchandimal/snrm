-- ============================================================================
-- verify_v5_event_region.sql — read-only checks that V5 gave a REGION-scoped
-- disruption event somewhere to name its region.
--
--   PowerShell      : mysql -u root -p snrm --table -e "source db/verify_v5_event_region.sql"
--   Git Bash / sh   : mysql -u root -p snrm < db/verify_v5_event_region.sql
--   MySQL Workbench : open, connect, execute the whole script
--
-- Nothing here writes. Unless a check says otherwise, ZERO ROWS is a pass.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. Flyway applied V5.  Expect five rows, all success = 1.
-- ---------------------------------------------------------------------------
SELECT installed_rank, version, description, success
FROM   flyway_schema_history
ORDER  BY installed_rank;


-- ---------------------------------------------------------------------------
-- 2. target_region exists and matches node.region exactly.
--
--    The resolution is literally `node.region = event.target_region` within one
--    network, so a difference in length, character set or collation would make
--    that comparison lie about which nodes an event hits. Expect 2 rows with
--    identical data_type, character_maximum_length, character_set_name and
--    collation_name.
-- ---------------------------------------------------------------------------
SELECT   table_name, column_name, data_type, character_maximum_length,
         character_set_name, collation_name, is_nullable
FROM     information_schema.columns
WHERE    table_schema = DATABASE()
  AND    (table_name, column_name) IN (('node','region'), ('disruption_event','target_region'))
ORDER BY table_name;


-- ---------------------------------------------------------------------------
-- 3. ck_event_target now constrains both halves.
--
--    Expect one row whose check_clause names target_region as well as
--    target_id. V2's version mentioned only target_id.
-- ---------------------------------------------------------------------------
SELECT constraint_name, check_clause
FROM   information_schema.check_constraints
WHERE  constraint_schema = DATABASE() AND constraint_name = 'ck_event_target';


-- ---------------------------------------------------------------------------
-- 4. ix_event_region exists — the query path for "which events target this
--    region?", which ix_event_target cannot serve because target_id is NULL on
--    every REGION row.
--    Expect 1 row: target_region at seq 1.
-- ---------------------------------------------------------------------------
SELECT   index_name, seq_in_index, column_name
FROM     information_schema.statistics
WHERE    table_schema = DATABASE() AND table_name = 'disruption_event'
  AND    index_name = 'ix_event_region'
ORDER BY seq_in_index;


-- ---------------------------------------------------------------------------
-- 5. ix_event_window is untouched and still backs fk_event_scenario.
--    Expect 3 rows: scenario_id, start_offset_seconds, duration_seconds.
-- ---------------------------------------------------------------------------
SELECT   index_name, seq_in_index, column_name
FROM     information_schema.statistics
WHERE    table_schema = DATABASE() AND table_name = 'disruption_event'
  AND    index_name = 'ix_event_window'
ORDER BY seq_in_index;


-- ---------------------------------------------------------------------------
-- 6. No row carries both halves of the reference, or neither.
--
--    ck_event_target makes this impossible, so it is a check on the constraint
--    rather than on the data. Expect ZERO ROWS.
-- ---------------------------------------------------------------------------
SELECT id, scenario_id, target_type, target_id, target_region,
       CASE
         WHEN target_type = 'REGION' AND target_region IS NULL THEN 'REGION with no region'
         WHEN target_type = 'REGION' AND target_id     IS NOT NULL THEN 'REGION carrying an id'
         WHEN target_type <> 'REGION' AND target_id     IS NULL THEN 'NODE/LINK with no id'
         ELSE 'NODE/LINK carrying a region'
       END AS problem
FROM   disruption_event
WHERE  (target_type =  'REGION' AND (target_region IS NULL OR target_id IS NOT NULL))
   OR  (target_type <> 'REGION' AND (target_id IS NULL OR target_region IS NOT NULL));


-- ---------------------------------------------------------------------------
-- 7. Every REGION event resolves to at least one node.
--
--    The service refuses an empty region (EVENT_TARGET_INVALID) because such an
--    event would run, complete, and show a network shrugging off a disruption it
--    never received. But an event is validated against the network it was
--    authored against, and a scenario is project-scoped, so a tag can
--    still be empty in a *sibling* variant. Rows here are therefore INFORMATIVE,
--    not failures: each names a (scenario, network) pair whose events would
--    strike nothing if a run were submitted against that network.
-- ---------------------------------------------------------------------------
SELECT   e.id AS event_id, s.name AS scenario, e.target_region, n.id AS network_id,
         n.name AS network, n.version
FROM     disruption_event e
JOIN     disruption_scenario s ON s.id = e.scenario_id
JOIN     network n             ON n.project_id = s.project_id
WHERE    e.target_type = 'REGION'
  AND    NOT EXISTS (SELECT 1 FROM node nd
                     WHERE nd.network_id = n.id AND nd.region = e.target_region)
ORDER BY e.id, n.id;
