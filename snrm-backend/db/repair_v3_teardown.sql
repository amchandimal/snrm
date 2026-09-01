-- ============================================================================
-- repair_v3_teardown.sql — put the schema back to its post-V2 state after
-- V3__time_units.sql failed partway through.
--
-- THIS SCRIPT WRITES. Read the safety note below before running it.
--
--   PowerShell      : mysql -u snrm_app -p snrm -e "source db/repair_v3_teardown.sql"
--   Git Bash / sh   : mysql -u snrm_app -p snrm < db/repair_v3_teardown.sql
--   MySQL Workbench : open, connect, execute the whole script
--
-- PowerShell has no `<` redirection operator, hence the client's own `source`
-- command; and not `Get-Content ... | mysql`, because -p reads the password
-- from stdin and would swallow the file. Run from the project root.
--
-- Whichever you use, it must be the mysql client or Workbench, not a JDBC tool:
-- section 0 defines a stored procedure with DELIMITER, which is a client-side
-- directive that only those two understand.
--
-- Section 8 clears the failed row from flyway_schema_history, which is what
-- `flyway repair` would do. It is done in SQL here because this project has no
-- Flyway Maven plugin — pom.xml carries flyway-core and flyway-mysql, and Spring
-- Boot's autoconfiguration drives them at startup, so there is no `flyway:repair`
-- goal to call. Afterwards:
--
--   mvnw.cmd spring-boot:run        (applies the corrected V3 from scratch)
--
-- WHY A TEARDOWN AND NOT A ROLL-FORWARD. MySQL does not roll back DDL, so a
-- failed migration stops wherever it stopped. Flyway will re-run the whole file
-- after a repair, and V3 is not written to be resumable — it would try to add
-- columns that already exist. Returning to a known state and replaying the
-- corrected migration in one clean pass is both simpler and verifiable.
--
-- SAFETY. Every statement is conditional on what is actually present, so the
-- script is idempotent and safe to run whatever V3 managed to do — including
-- nothing. It touches only the artefacts V3 creates:
--
--   * it drops the columns V3 added. Their contents are derived from the
--     pre-V3 columns, so nothing original is lost as long as those still exist;
--   * if V3 got as far as dropping the pre-V3 columns, section 3 restores them
--     and their values from v3_time_units_audit before anything else is dropped;
--   * it restores the V2 CHECK constraints and ix_event_scenario;
--   * it drops v3_time_units_audit last, once nothing needs it.
--
-- It does NOT touch project, product, simulation_run, run_timeseries,
-- configuration_variant, or any row of any table beyond the columns above.
--
-- Run db/diagnose_v3_state.sql first if you have not: if its check 4 shows all
-- seven pre-V3 columns present, this teardown is completely lossless.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 0. A conditional-DDL helper.
--
-- MySQL has no DROP CHECK IF EXISTS, no DROP INDEX IF EXISTS, and no ADD COLUMN
-- IF NOT EXISTS, so each statement is guarded by a count from information_schema
-- and run through PREPARE. The procedure is dropped again at the end.
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS v3_exec_if;

DELIMITER $$
CREATE PROCEDURE v3_exec_if(IN should_run INT, IN sql_text TEXT)
BEGIN
  IF should_run > 0 THEN
    SET @v3_sql = sql_text;
    PREPARE v3_stmt FROM @v3_sql;
    EXECUTE v3_stmt;
    DEALLOCATE PREPARE v3_stmt;
  END IF;
END$$
DELIMITER ;

-- Present-or-not helpers, as reusable subqueries would be if MySQL had them.
-- Each CALL below inlines one; the shape is always the same.
--   column   : information_schema.columns
--   check    : information_schema.table_constraints, constraint_type = 'CHECK'
--   index    : information_schema.statistics
--   table    : information_schema.tables


-- ---------------------------------------------------------------------------
-- 1. Drop every CHECK constraint V3 touches, in either generation.
--
-- First, because a CHECK blocks the drop of any column it references (errno
-- 3959) and V3 reuses four V2 names — ck_node_capacity, ck_link_capacity,
-- ck_link_lead_time, ck_node_product_demand, ck_event_window — for constraints
-- over the NEW columns. Dropping by name clears whichever generation is there;
-- section 6 puts the V2 definitions back.
-- ---------------------------------------------------------------------------
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_network_period'),
                'ALTER TABLE network DROP CHECK ck_network_period');
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_network_horizon'),
                'ALTER TABLE network DROP CHECK ck_network_horizon');
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_node_processing_time'),
                'ALTER TABLE node DROP CHECK ck_node_processing_time');
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_node_capacity'),
                'ALTER TABLE node DROP CHECK ck_node_capacity');
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_link_capacity'),
                'ALTER TABLE link DROP CHECK ck_link_capacity');
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_link_lead_time'),
                'ALTER TABLE link DROP CHECK ck_link_lead_time');
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_node_product_demand'),
                'ALTER TABLE node_product DROP CHECK ck_node_product_demand');
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_event_window'),
                'ALTER TABLE disruption_event DROP CHECK ck_event_window');


-- ---------------------------------------------------------------------------
-- 2. Restore ix_event_scenario before dropping ix_event_window.
--
-- The same foreign-key trap that broke the migration, in reverse. Whichever of
-- the two indexes exists is the one InnoDB is using to satisfy fk_event_scenario
-- — both lead with scenario_id — so the replacement has to exist before the
-- incumbent is dropped, or the drop fails with errno 1553.
--
-- Guarded on start_period existing: if V3 dropped that column, section 3 puts it
-- back first and this runs to completion on a second pass. The CALL is repeated
-- after section 3 for exactly that reason; whichever fires first, the other is a
-- no-op.
-- ---------------------------------------------------------------------------
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.columns    WHERE table_schema = DATABASE() AND table_name = 'disruption_event' AND column_name = 'start_period')
              * (1 - (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'disruption_event' AND index_name = 'ix_event_scenario')),
                'CREATE INDEX ix_event_scenario ON disruption_event (scenario_id, start_period)');


-- ---------------------------------------------------------------------------
-- 3. Restore the pre-V3 columns, if V3 got as far as dropping them.
--
-- Only reachable when v3_time_units_audit exists — it is written before the
-- drops, so if the columns are gone the audit table is necessarily there.
-- Values come straight back out of old_value; the columns are added nullable,
-- populated, then tightened to their V2 nullability.
--
-- If your diagnose output showed all seven columns present, every statement in
-- this section is a no-op.
-- ---------------------------------------------------------------------------
SET @audit_present := (SELECT COUNT(*) FROM information_schema.tables
                       WHERE table_schema = DATABASE() AND table_name = 'v3_time_units_audit');

-- node.capacity_per_period
CALL v3_exec_if(@audit_present * (1 - (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'node' AND column_name = 'capacity_per_period')),
                'ALTER TABLE node ADD COLUMN capacity_per_period DOUBLE NULL');
CALL v3_exec_if(@audit_present,
                'UPDATE node n JOIN v3_time_units_audit a ON a.quantity = ''node.capacity'' AND a.row_key = CAST(n.id AS CHAR) SET n.capacity_per_period = a.old_value');

-- link.lead_time and link.capacity_per_period
CALL v3_exec_if(@audit_present * (1 - (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'link' AND column_name = 'lead_time')),
                'ALTER TABLE link ADD COLUMN lead_time INT NOT NULL DEFAULT 0');
CALL v3_exec_if(@audit_present * (1 - (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'link' AND column_name = 'capacity_per_period')),
                'ALTER TABLE link ADD COLUMN capacity_per_period DOUBLE NULL');
CALL v3_exec_if(@audit_present,
                'UPDATE link l JOIN v3_time_units_audit a ON a.quantity = ''link.lead_time'' AND a.row_key = CAST(l.id AS CHAR) SET l.lead_time = a.old_value');
CALL v3_exec_if(@audit_present,
                'UPDATE link l JOIN v3_time_units_audit a ON a.quantity = ''link.capacity'' AND a.row_key = CAST(l.id AS CHAR) SET l.capacity_per_period = a.old_value');

-- node_product.demand_per_period and node_product.holding_cost
CALL v3_exec_if(@audit_present * (1 - (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'node_product' AND column_name = 'demand_per_period')),
                'ALTER TABLE node_product ADD COLUMN demand_per_period DOUBLE NOT NULL DEFAULT 0');
CALL v3_exec_if(@audit_present * (1 - (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'node_product' AND column_name = 'holding_cost')),
                'ALTER TABLE node_product ADD COLUMN holding_cost DOUBLE NOT NULL DEFAULT 0');
CALL v3_exec_if(@audit_present,
                'UPDATE node_product np JOIN v3_time_units_audit a ON a.quantity = ''node_product.demand'' AND a.row_key = CONCAT(np.node_id, '':'', np.product_id) SET np.demand_per_period = a.old_value');
CALL v3_exec_if(@audit_present,
                'UPDATE node_product np JOIN v3_time_units_audit a ON a.quantity = ''node_product.holding_cost'' AND a.row_key = CONCAT(np.node_id, '':'', np.product_id) SET np.holding_cost = a.old_value');

-- disruption_event.start_period and disruption_event.duration
CALL v3_exec_if(@audit_present * (1 - (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'disruption_event' AND column_name = 'start_period')),
                'ALTER TABLE disruption_event ADD COLUMN start_period INT NOT NULL DEFAULT 0');
CALL v3_exec_if(@audit_present * (1 - (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'disruption_event' AND column_name = 'duration')),
                'ALTER TABLE disruption_event ADD COLUMN duration INT NOT NULL DEFAULT 1');
CALL v3_exec_if(@audit_present,
                'UPDATE disruption_event e JOIN v3_time_units_audit a ON a.quantity = ''disruption_event.start_offset'' AND a.row_key = CAST(e.id AS CHAR) SET e.start_period = a.old_value');
CALL v3_exec_if(@audit_present,
                'UPDATE disruption_event e JOIN v3_time_units_audit a ON a.quantity = ''disruption_event.duration'' AND a.row_key = CAST(e.id AS CHAR) SET e.duration = a.old_value');

-- V2 declared these two without a DEFAULT; drop the one added above for the
-- restore now that every row has its value back.
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'disruption_event' AND column_name = 'start_period' AND column_default IS NOT NULL),
                'ALTER TABLE disruption_event MODIFY COLUMN start_period INT NOT NULL');
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'disruption_event' AND column_name = 'duration' AND column_default IS NOT NULL),
                'ALTER TABLE disruption_event MODIFY COLUMN duration INT NOT NULL');

-- Second attempt at ix_event_scenario, now that start_period is certain to exist.
CALL v3_exec_if((SELECT COUNT(*) FROM information_schema.columns    WHERE table_schema = DATABASE() AND table_name = 'disruption_event' AND column_name = 'start_period')
              * (1 - (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'disruption_event' AND index_name = 'ix_event_scenario')),
                'CREATE INDEX ix_event_scenario ON disruption_event (scenario_id, start_period)');


-- ---------------------------------------------------------------------------
-- 4. Drop ix_event_window, now that ix_event_scenario is back to hold the
--    foreign key up.
-- ---------------------------------------------------------------------------
CALL v3_exec_if((SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'disruption_event' AND index_name = 'ix_event_window'),
                'ALTER TABLE disruption_event DROP INDEX ix_event_window');


-- ---------------------------------------------------------------------------
-- 5. Drop the columns V3 added.
--
-- One statement per table, assembled from whichever of them are actually there,
-- so a partial add tears down as cleanly as a complete one. 'DO 0' is MySQL's
-- no-op statement, used when none are present.
-- ---------------------------------------------------------------------------
SELECT IFNULL(CONCAT('ALTER TABLE network DROP COLUMN ', GROUP_CONCAT(column_name SEPARATOR ', DROP COLUMN ')), 'DO 0') INTO @sql
FROM   information_schema.columns
WHERE  table_schema = DATABASE() AND table_name = 'network'
  AND  column_name IN ('period_length_value','period_length_unit','period_length_seconds','horizon_periods','rounding_policy');
CALL v3_exec_if(1, @sql);

SELECT IFNULL(CONCAT('ALTER TABLE node DROP COLUMN ', GROUP_CONCAT(column_name SEPARATOR ', DROP COLUMN ')), 'DO 0') INTO @sql
FROM   information_schema.columns
WHERE  table_schema = DATABASE() AND table_name = 'node'
  AND  column_name IN ('capacity_value','capacity_time_unit','processing_time_value','processing_time_unit','processing_time_seconds');
CALL v3_exec_if(1, @sql);

SELECT IFNULL(CONCAT('ALTER TABLE link DROP COLUMN ', GROUP_CONCAT(column_name SEPARATOR ', DROP COLUMN ')), 'DO 0') INTO @sql
FROM   information_schema.columns
WHERE  table_schema = DATABASE() AND table_name = 'link'
  AND  column_name IN ('lead_time_value','lead_time_unit','lead_time_seconds','capacity_value','capacity_time_unit');
CALL v3_exec_if(1, @sql);

SELECT IFNULL(CONCAT('ALTER TABLE node_product DROP COLUMN ', GROUP_CONCAT(column_name SEPARATOR ', DROP COLUMN ')), 'DO 0') INTO @sql
FROM   information_schema.columns
WHERE  table_schema = DATABASE() AND table_name = 'node_product'
  AND  column_name IN ('demand_value','demand_time_unit','holding_cost_value','holding_cost_time_unit');
CALL v3_exec_if(1, @sql);

SELECT IFNULL(CONCAT('ALTER TABLE disruption_event DROP COLUMN ', GROUP_CONCAT(column_name SEPARATOR ', DROP COLUMN ')), 'DO 0') INTO @sql
FROM   information_schema.columns
WHERE  table_schema = DATABASE() AND table_name = 'disruption_event'
  AND  column_name IN ('start_offset_value','start_offset_unit','start_offset_seconds','duration_value','duration_unit','duration_seconds');
CALL v3_exec_if(1, @sql);

SELECT IFNULL(CONCAT('ALTER TABLE metric_result DROP COLUMN ', GROUP_CONCAT(column_name SEPARATOR ', DROP COLUMN ')), 'DO 0') INTO @sql
FROM   information_schema.columns
WHERE  table_schema = DATABASE() AND table_name = 'metric_result'
  AND  column_name IN ('display_unit');
CALL v3_exec_if(1, @sql);


-- ---------------------------------------------------------------------------
-- 6. Put the V2 CHECK constraints back, exactly as V2__domain.sql declares them.
-- ---------------------------------------------------------------------------
CALL v3_exec_if(1 - (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_node_capacity'),
                'ALTER TABLE node ADD CONSTRAINT ck_node_capacity CHECK (capacity_per_period IS NULL OR capacity_per_period >= 0)');
CALL v3_exec_if(1 - (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_link_capacity'),
                'ALTER TABLE link ADD CONSTRAINT ck_link_capacity CHECK (capacity_per_period IS NULL OR capacity_per_period >= 0)');
CALL v3_exec_if(1 - (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_link_lead_time'),
                'ALTER TABLE link ADD CONSTRAINT ck_link_lead_time CHECK (lead_time >= 0)');
CALL v3_exec_if(1 - (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_node_product_demand'),
                'ALTER TABLE node_product ADD CONSTRAINT ck_node_product_demand CHECK (demand_per_period >= 0)');
CALL v3_exec_if(1 - (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND constraint_name = 'ck_event_window'),
                'ALTER TABLE disruption_event ADD CONSTRAINT ck_event_window CHECK (start_period >= 0 AND duration >= 1)');


-- ---------------------------------------------------------------------------
-- 7. Drop the audit table and the helper. Nothing needs either any more; the
--    corrected V3 rebuilds the audit table when it runs.
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS v3_time_units_audit;
DROP PROCEDURE IF EXISTS v3_exec_if;


-- ---------------------------------------------------------------------------
-- 8. Clear the failed migration from Flyway's history — the repair step.
--
-- Flyway refuses to run while its history contains a failed row ("Detected
-- failed migration to version 3"). Deleting that row is exactly what `flyway
-- repair` does for a failed migration on a database that cannot roll back DDL;
-- doing it here saves adding a Maven plugin for one statement.
--
-- Scoped to success = 0, so no successfully applied migration can be touched:
-- V1 and V2 keep their rows and their checksums, and Flyway will re-run only V3.
-- ---------------------------------------------------------------------------
DELETE FROM flyway_schema_history WHERE success = 0;


-- ---------------------------------------------------------------------------
-- 9. Confirm the V2 state is back.
--
--    Expect: first query 7 rows (every pre-V3 column present), second query
--    ZERO rows (no V3 column left), third query the eleven V2 CHECK constraints,
--    fourth query PRIMARY / ix_event_scenario / ix_event_target and no
--    ix_event_window, fifth query V1 and V2 only, both success = 1.
-- ---------------------------------------------------------------------------
SELECT   table_name, column_name, column_type, is_nullable, column_default
FROM     information_schema.columns
WHERE    table_schema = DATABASE()
  AND    (   (table_name = 'node'             AND column_name = 'capacity_per_period')
          OR (table_name = 'link'             AND column_name IN ('lead_time','capacity_per_period'))
          OR (table_name = 'node_product'     AND column_name IN ('demand_per_period','holding_cost'))
          OR (table_name = 'disruption_event' AND column_name IN ('start_period','duration')))
ORDER BY table_name, column_name;

SELECT   table_name, column_name, 'V3 COLUMN STILL PRESENT' AS problem
FROM     information_schema.columns
WHERE    table_schema = DATABASE()
  AND    column_name IN ('period_length_value','period_length_unit','period_length_seconds',
                         'horizon_periods','rounding_policy','capacity_value','capacity_time_unit',
                         'processing_time_value','processing_time_unit','processing_time_seconds',
                         'lead_time_value','lead_time_unit','lead_time_seconds',
                         'demand_value','demand_time_unit','holding_cost_value','holding_cost_time_unit',
                         'start_offset_value','start_offset_unit','start_offset_seconds',
                         'duration_value','duration_unit','duration_seconds','display_unit');

SELECT   tc.table_name, tc.constraint_name, cc.check_clause
FROM     information_schema.table_constraints tc
JOIN     information_schema.check_constraints cc
      ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name
WHERE    tc.constraint_schema = DATABASE() AND tc.constraint_type = 'CHECK'
ORDER BY tc.table_name, tc.constraint_name;

SELECT   index_name, seq_in_index, column_name
FROM     information_schema.statistics
WHERE    table_schema = DATABASE() AND table_name = 'disruption_event'
ORDER BY index_name, seq_in_index;

SELECT   installed_rank, version, description, success
FROM     flyway_schema_history
ORDER BY installed_rank;
