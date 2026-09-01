-- ============================================================================
-- verify_v2_schema.sql — read-only checks that the migrated schema matches the
-- ER model and the conventions.
--
-- Run AFTER the application has started once (Flyway applies V2 at startup):
--
--   MySQL Workbench : open this file, connect, execute the whole script
--   mysql CLI       : mysql -u snrm_app -p snrm < db/verify_v2_schema.sql
--
-- Nothing here writes. Expected results are stated above each query.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Flyway applied V2 cleanly.
--    Expect two rows, V1 and V2, both success = 1.
-- ---------------------------------------------------------------------------
SELECT installed_rank, version, description, type, success, installed_on
FROM   snrm.flyway_schema_history
ORDER  BY installed_rank;

-- ---------------------------------------------------------------------------
-- 2. Every entity of the ER model has a table, and nothing else does.
--    Expect exactly these 12 rows (flyway_schema_history is excluded):
--      configuration_variant, disruption_event, disruption_scenario, link,
--      metric_result, network, node, node_product, product, project,
--      run_timeseries, simulation_run
--    All InnoDB / utf8mb4.
-- ---------------------------------------------------------------------------
SELECT   t.table_name,
         t.engine,
         t.table_collation,
         COUNT(c.column_name) AS column_count
FROM     information_schema.tables t
JOIN     information_schema.columns c
      ON c.table_schema = t.table_schema AND c.table_name = t.table_name
WHERE    t.table_schema = 'snrm'
  AND    t.table_name <> 'flyway_schema_history'
GROUP BY t.table_name, t.engine, t.table_collation
ORDER BY t.table_name;

-- ---------------------------------------------------------------------------
-- 3. ENUM columns carry exactly the literals of the model, by the naming
--    convention. Expect 6 rows:
--      configuration_variant.generated_by  enum('MANUAL','SEARCH')
--      disruption_event.recovery_profile   enum('STEP','LINEAR','EXPONENTIAL')
--      disruption_event.target_type        enum('NODE','LINK','REGION')
--      metric_result.scope                 enum('NETWORK','NODE','LINK')
--      node.type                           enum('SUPPLIER','PLANT','DC','CUSTOMER')
--      simulation_run.status               enum('QUEUED','RUNNING','DONE','FAILED','CANCELLED')
--    These must match the Java enums character for character, or Hibernate's
--    ddl-auto=validate will reject the column at startup.
-- ---------------------------------------------------------------------------
SELECT table_name, column_name, column_type, is_nullable, column_default
FROM   information_schema.columns
WHERE  table_schema = 'snrm' AND data_type = 'enum'
ORDER  BY table_name, column_name;

-- ---------------------------------------------------------------------------
-- 4. node carries the canvas coordinates alongside the geographic
--    pair, and they are distinct nullable columns.
--    Expect 5 rows: region varchar(60), lat, lng, pos_x, pos_y — all double,
--    all YES-nullable.
-- ---------------------------------------------------------------------------
SELECT column_name, column_type, is_nullable, ordinal_position
FROM   information_schema.columns
WHERE  table_schema = 'snrm' AND table_name = 'node'
  AND  column_name IN ('region', 'lat', 'lng', 'pos_x', 'pos_y')
ORDER  BY ordinal_position;

-- ---------------------------------------------------------------------------
-- 5. Unique keys and composite indexes on the query paths.
--    Spot-check for:
--      node                  uq_node               (network_id, name)
--      metric_result         ix_mr                 (network_id, run_id, metric_code)
--      link                  uq_link               (network_id, source_node_id, target_node_id)
--      network               uq_network            (project_id, name, version)
--      simulation_run        ix_run_network_status (network_id, status)        <- the guard
--      configuration_variant uq_variant_network    (network_id)                <- ER 1:1
--      node_product          PRIMARY               (node_id, product_id)       <- natural key, no surrogate id
--      run_timeseries        PRIMARY               (run_id, period)            <- natural key, no surrogate id
-- ---------------------------------------------------------------------------
SELECT   table_name,
         index_name,
         CASE non_unique WHEN 0 THEN 'UNIQUE' ELSE 'index' END AS kind,
         GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns
FROM     information_schema.statistics
WHERE    table_schema = 'snrm' AND table_name <> 'flyway_schema_history'
GROUP BY table_name, index_name, non_unique
ORDER BY table_name, index_name;

-- ---------------------------------------------------------------------------
-- 6. Relationships of the ER model, as foreign keys.
--    Expect 18 rows, every delete_rule CASCADE: the schema models ownership,
--    while network immutability is enforced in NetworkMutationGuard, not here.
--    Note there is no FK on disruption_event.target_id (polymorphic by
--    target_type) or on project.owner_id (no user table in Phase 1).
-- ---------------------------------------------------------------------------
SELECT   rc.table_name,
         rc.constraint_name,
         kcu.column_name,
         rc.referenced_table_name,
         rc.delete_rule
FROM     information_schema.referential_constraints rc
JOIN     information_schema.key_column_usage kcu
      ON kcu.constraint_schema = rc.constraint_schema
     AND kcu.constraint_name   = rc.constraint_name
     AND kcu.table_name        = rc.table_name
WHERE    rc.constraint_schema = 'snrm'
ORDER BY rc.table_name, rc.constraint_name;

-- ---------------------------------------------------------------------------
-- 7. Domain ranges that the schema enforces directly.
--    Expect failure_prob, severity and probability bounded to [0,1], no
--    self-loops on link, and disruption targets required for NODE/LINK.
-- ---------------------------------------------------------------------------
SELECT   tc.table_name, cc.constraint_name, cc.check_clause
FROM     information_schema.check_constraints cc
JOIN     information_schema.table_constraints tc
      ON tc.constraint_schema = cc.constraint_schema
     AND tc.constraint_name   = cc.constraint_name
WHERE    cc.constraint_schema = 'snrm'
ORDER BY tc.table_name, cc.constraint_name;

-- ---------------------------------------------------------------------------
-- 8. Auditing timestamps on every entity.
--    Expect ZERO rows — any table listed is missing created_at or updated_at.
-- ---------------------------------------------------------------------------
SELECT   t.table_name AS table_missing_audit_columns
FROM     information_schema.tables t
WHERE    t.table_schema = 'snrm'
  AND    t.table_name <> 'flyway_schema_history'
  AND    (SELECT COUNT(*)
          FROM   information_schema.columns c
          WHERE  c.table_schema = t.table_schema
            AND  c.table_name   = t.table_name
            AND  c.column_name IN ('created_at', 'updated_at')
            AND  c.data_type    = 'datetime') < 2;

-- ---------------------------------------------------------------------------
-- 9. The immutability guard's query path is index-only.
--    Expect possible_keys / key = ix_run_network_status with Extra containing
--    "Using index" — the check in front of every network edit never reads the
--    table itself. On a still-empty simulation_run, MySQL may short-circuit to
--    "no matching row in const table"; insert a run first if you want to see
--    the plan proper.
-- ---------------------------------------------------------------------------
EXPLAIN
SELECT 1
FROM   snrm.simulation_run
WHERE  network_id = 1
  AND  status IN ('QUEUED', 'RUNNING', 'DONE')
LIMIT  1;
