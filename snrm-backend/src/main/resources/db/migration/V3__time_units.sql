-- ============================================================================
-- V3__time_units.sql — adjustable time and rate units (FR-13).
--
-- WHAT CHANGES
--
-- Before V3 every temporal quantity in the model was counted in
-- "periods", and a period was whatever the user had in mind. A lead time of 3
-- meant three periods; a capacity of 500 meant 500 per period. Nothing recorded
-- how long a period was, so two networks with different period lengths could
-- not be compared, and a scenario replayed against both meant different things
-- in each.
--
-- After V3 each such quantity is stated in its own unit:
--
--   * a DURATION is (value, unit, seconds) — the number the user typed, the
--     unit they typed it in, and the derived total in seconds;
--   * a RATE is (value, time_unit) — a quantity per one of that unit. Rates
--     carry no derived column: their normalised form is a per-second figure
--     nothing queries, sorts or indexes by (see com.snrm.common.Rate).
--
-- The *_seconds columns are what make durations comparable across units. They
-- are derived data, maintained by the owning entity's @PrePersist/@PreUpdate
-- (com.snrm.common.DurationAmount) and by this migration for existing rows.
-- Do not write them by hand.
--
-- THE BACKFILL, AND WHY IT PRESERVES MEANING
--
-- The interpretation rule is a single sentence: an existing integer counted
-- periods, so it becomes that many periods' worth of real time.
--
--   1. Give every network a period. The columns are new, so every existing row
--      is unset, and unset defaults to 1 DAY per the design note.
--      period_length_seconds follows from the pair.
--   2. A duration of n periods becomes
--         value   = n * period_length_value
--         unit    = period_length_unit
--         seconds = n * period_length_seconds
--      which is exactly the same instant of elapsed time, restated. The three
--      are self-consistent because
--         value * secondsOf(unit)
--           = n * period_length_value * secondsOf(period_length_unit)
--           = n * period_length_seconds.
--   3. A rate of x per period becomes
--         value     = x / period_length_value
--         time_unit = period_length_unit
--      i.e. the same throughput restated per unit rather than per period. With
--      period_length_value = 1 — true of every row this migration touches,
--      since the column did not exist to be set otherwise — the number is
--      unchanged and only the label moves from "per period" to "per DAY".
--
-- Because every existing network backfills to a 1 DAY period, steps 2 and 3
-- leave every stored number identical and only attach a unit to it. The general
-- forms above are written out anyway so the migration stays correct if it is
-- ever replayed against a database where the period columns were populated
-- first.
--
-- secondsOf() appears exactly once, in step 1, as a CASE over the unit. Every
-- later conversion multiplies by network.period_length_seconds instead of
-- repeating it, which is both shorter and impossible to get inconsistently
-- wrong.
--
-- SAFETY
--
-- Additive first, destructive last. New columns are added and populated while
-- the old ones are still present and unread; the drops come only after every
-- new column has a value. Divisions guard against a zero period with NULLIF
-- even though ck_network_period makes one impossible, so a partially applied
-- state cannot produce infinities.
--
-- Before the old columns are dropped, their values are copied into
-- v3_time_units_audit together with what they became. That table is the only
-- way to check the backfill after the fact — once a column is dropped there is
-- nothing left to compare against. db/verify_v3_backfill.sql reads it and tells
-- you how to drop it when you are satisfied.
--
-- CHECK constraints and indexes over a dropped column are dropped explicitly:
-- MySQL 8 refuses to drop a column a CHECK still references (ERROR 3959), and
-- an index would otherwise be silently rewritten to something narrower than it
-- was meant to be.
-- ============================================================================


-- ===========================================================================
-- 1. network — the clock every other duration is discretised against.
-- ===========================================================================

-- Added nullable so the "default the period where unset" step below is a real,
-- visible backfill rather than something buried in a DEFAULT clause.
ALTER TABLE network
  ADD COLUMN period_length_value   DOUBLE NULL AFTER is_baseline,
  ADD COLUMN period_length_unit    ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NULL AFTER period_length_value,
  ADD COLUMN period_length_seconds DOUBLE NULL AFTER period_length_unit,
  ADD COLUMN horizon_periods       INT    NULL AFTER period_length_seconds,
  ADD COLUMN rounding_policy       ENUM('NEAREST','UP','DOWN') NULL AFTER horizon_periods;

-- The period defaults to 1 DAY where unset. Every pre-V3 row is
-- unset by construction — the columns did not exist — so this is what fixes the
-- meaning of every "period" recorded anywhere else in the schema.
--
-- horizon_periods defaults to 52, the horizon the performance target is
-- stated against (a 52-period run). It is a starting value, not a claim about
-- any particular network; change it per network afterwards.
--
-- rounding_policy defaults to NEAREST: unbiased over many values, where UP and
-- DOWN systematically lengthen or shorten every duration that does not divide
-- evenly into a period.
UPDATE network
SET    period_length_value = COALESCE(period_length_value, 1),
       period_length_unit  = COALESCE(period_length_unit, 'DAY'),
       horizon_periods     = COALESCE(horizon_periods, 52),
       rounding_policy     = COALESCE(rounding_policy, 'NEAREST');

-- The one and only expansion of TimeUnit.secondsOf(). MONTH is 30 days and YEAR
-- is 365 days, fixed — durations must convert identically regardless of the
-- date a run starts on, or reproducibility fails. Keep this CASE in
-- step with com.snrm.common.TimeUnit.
UPDATE network
SET    period_length_seconds = period_length_value * CASE period_length_unit
           WHEN 'SECOND' THEN 1
           WHEN 'MINUTE' THEN 60
           WHEN 'HOUR'   THEN 3600
           WHEN 'DAY'    THEN 86400
           WHEN 'WEEK'   THEN 604800
           WHEN 'MONTH'  THEN 2592000
           WHEN 'YEAR'   THEN 31536000
       END;

ALTER TABLE network
  MODIFY COLUMN period_length_value   DOUBLE NOT NULL DEFAULT 1,
  MODIFY COLUMN period_length_unit    ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NOT NULL DEFAULT 'DAY',
  MODIFY COLUMN period_length_seconds DOUBLE NOT NULL DEFAULT 86400,
  MODIFY COLUMN horizon_periods       INT    NOT NULL DEFAULT 52,
  MODIFY COLUMN rounding_policy       ENUM('NEAREST','UP','DOWN') NOT NULL DEFAULT 'NEAREST',
  -- A zero or negative period would make every discretisation either
  -- a division by zero or a run backwards through time.
  ADD CONSTRAINT ck_network_period  CHECK (period_length_value > 0 AND period_length_seconds > 0),
  ADD CONSTRAINT ck_network_horizon CHECK (horizon_periods >= 1);


-- ===========================================================================
-- 2. node — capacity becomes a rate; processing_time is new.
-- ===========================================================================

ALTER TABLE node
  ADD COLUMN capacity_value         DOUBLE NULL AFTER capacity_per_period,
  ADD COLUMN capacity_time_unit     ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NULL AFTER capacity_value,
  ADD COLUMN processing_time_value  DOUBLE NULL AFTER capacity_time_unit,
  ADD COLUMN processing_time_unit   ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NULL AFTER processing_time_value,
  ADD COLUMN processing_time_seconds DOUBLE NULL AFTER processing_time_unit;

-- capacity: x per period -> (x / period_length_value) per period_length_unit.
-- A NULL capacity_per_period meant "unconstrained" and still does, as a NULL
-- capacity_value; the unit is filled in regardless so the pair is well-formed
-- and a value set later does not need a unit chosen for it.
--
-- processing_time is new and has no predecessor to preserve: it backfills to
-- zero, which is precisely the behaviour every existing network already had.
UPDATE node n
JOIN   network w ON w.id = n.network_id
SET    n.capacity_value          = n.capacity_per_period / NULLIF(w.period_length_value, 0),
       n.capacity_time_unit      = w.period_length_unit,
       n.processing_time_value   = 0,
       n.processing_time_unit    = w.period_length_unit,
       n.processing_time_seconds = 0;

ALTER TABLE node
  MODIFY COLUMN capacity_time_unit      ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NOT NULL DEFAULT 'DAY',
  MODIFY COLUMN processing_time_value   DOUBLE NOT NULL DEFAULT 0,
  MODIFY COLUMN processing_time_unit    ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NOT NULL DEFAULT 'DAY',
  MODIFY COLUMN processing_time_seconds DOUBLE NOT NULL DEFAULT 0;


-- ===========================================================================
-- 3. link — lead_time becomes a duration, capacity a rate.
-- ===========================================================================

ALTER TABLE link
  ADD COLUMN lead_time_value    DOUBLE NULL AFTER lead_time,
  ADD COLUMN lead_time_unit     ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NULL AFTER lead_time_value,
  ADD COLUMN lead_time_seconds  DOUBLE NULL AFTER lead_time_unit,
  ADD COLUMN capacity_value     DOUBLE NULL AFTER capacity_per_period,
  ADD COLUMN capacity_time_unit ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NULL AFTER capacity_value;

-- lead_time: n periods -> n * period_length_value of period_length_unit, whose
-- second-count is n * period_length_seconds. capacity as in node above.
UPDATE link l
JOIN   network w ON w.id = l.network_id
SET    l.lead_time_value    = l.lead_time * w.period_length_value,
       l.lead_time_unit     = w.period_length_unit,
       l.lead_time_seconds  = l.lead_time * w.period_length_seconds,
       l.capacity_value     = l.capacity_per_period / NULLIF(w.period_length_value, 0),
       l.capacity_time_unit = w.period_length_unit;

ALTER TABLE link
  MODIFY COLUMN lead_time_value    DOUBLE NOT NULL DEFAULT 0,
  MODIFY COLUMN lead_time_unit     ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NOT NULL DEFAULT 'DAY',
  MODIFY COLUMN lead_time_seconds  DOUBLE NOT NULL DEFAULT 0,
  MODIFY COLUMN capacity_time_unit ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NOT NULL DEFAULT 'DAY';


-- ===========================================================================
-- 4. node_product — demand and holding cost become rates.
--
-- Reached through node to its network: node_product has no network_id of its
-- own, and a product is project-scoped, so the node is the only path to a
-- period.
--
-- initial_inventory and safety_stock are deliberately untouched. They are stock
-- levels, not flows — a quantity on hand has no time dimension to state.
-- ===========================================================================

ALTER TABLE node_product
  ADD COLUMN demand_value           DOUBLE NULL AFTER demand_per_period,
  ADD COLUMN demand_time_unit       ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NULL AFTER demand_value,
  ADD COLUMN holding_cost_value     DOUBLE NULL AFTER holding_cost,
  ADD COLUMN holding_cost_time_unit ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NULL AFTER holding_cost_value;

UPDATE node_product np
JOIN   node    n ON n.id = np.node_id
JOIN   network w ON w.id = n.network_id
SET    np.demand_value           = np.demand_per_period / NULLIF(w.period_length_value, 0),
       np.demand_time_unit       = w.period_length_unit,
       np.holding_cost_value     = np.holding_cost / NULLIF(w.period_length_value, 0),
       np.holding_cost_time_unit = w.period_length_unit;

ALTER TABLE node_product
  MODIFY COLUMN demand_value           DOUBLE NOT NULL DEFAULT 0,
  MODIFY COLUMN demand_time_unit       ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NOT NULL DEFAULT 'DAY',
  MODIFY COLUMN holding_cost_value     DOUBLE NOT NULL DEFAULT 0,
  MODIFY COLUMN holding_cost_time_unit ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NOT NULL DEFAULT 'DAY';


-- ===========================================================================
-- 5. disruption_event — start and duration become absolute durations.
--
-- WHICH PERIOD? A scenario is project-scoped so it can be replayed against
-- every variant, while a period belongs to a network — so there
-- is no single correct answer for an event, only a best available one. The
-- backfill resolves, in order:
--
--   a. the project's baseline network — the network the others are compared
--      against, so its clock is the one the events were most likely written
--      against;
--   b. failing a baseline, the project's lowest-id network, i.e. its oldest;
--   c. failing any network at all, 1 DAY, matching the default in step 1.
--
-- This ambiguity is exactly what FR-13 removes going forward: once timing is
-- absolute, an event means the same thing against every variant and no network
-- has to be consulted to read it. It only exists because the old data had to be
-- interpreted through a period that was never recorded. If a project's networks
-- did not all share a period, re-check its events by hand — v3_time_units_audit
-- records which period_seconds each event was interpreted through.
-- ===========================================================================

ALTER TABLE disruption_event
  ADD COLUMN start_offset_value   DOUBLE NULL AFTER start_period,
  ADD COLUMN start_offset_unit    ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NULL AFTER start_offset_value,
  ADD COLUMN start_offset_seconds DOUBLE NULL AFTER start_offset_unit,
  ADD COLUMN duration_value       DOUBLE NULL AFTER duration,
  ADD COLUMN duration_unit        ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NULL AFTER duration_value,
  ADD COLUMN duration_seconds     DOUBLE NULL AFTER duration_unit;

-- One row per scenario: the period its project's events are interpreted
-- through, resolved by rule (a) then (b). Scenarios whose project has no
-- network at all are absent and fall through to rule (c) below.
CREATE TEMPORARY TABLE v3_scenario_period AS
SELECT   s.id            AS scenario_id,
         w.period_length_value   AS period_value,
         w.period_length_unit    AS period_unit,
         w.period_length_seconds AS period_seconds
FROM     disruption_scenario s
JOIN     network w
      ON w.id = (SELECT   c.id
                 FROM     network c
                 WHERE    c.project_id = s.project_id
                 ORDER BY c.is_baseline DESC, c.id ASC
                 LIMIT    1);

CREATE INDEX ix_v3_scenario_period ON v3_scenario_period (scenario_id);

UPDATE disruption_event e
LEFT JOIN v3_scenario_period p ON p.scenario_id = e.scenario_id
SET    e.start_offset_value   = e.start_period * COALESCE(p.period_value, 1),
       e.start_offset_unit    = COALESCE(p.period_unit, 'DAY'),
       e.start_offset_seconds = e.start_period * COALESCE(p.period_seconds, 86400),
       e.duration_value       = e.duration * COALESCE(p.period_value, 1),
       e.duration_unit        = COALESCE(p.period_unit, 'DAY'),
       e.duration_seconds     = e.duration * COALESCE(p.period_seconds, 86400);

ALTER TABLE disruption_event
  MODIFY COLUMN start_offset_value   DOUBLE NOT NULL DEFAULT 0,
  MODIFY COLUMN start_offset_unit    ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NOT NULL DEFAULT 'DAY',
  MODIFY COLUMN start_offset_seconds DOUBLE NOT NULL DEFAULT 0,
  MODIFY COLUMN duration_value       DOUBLE NOT NULL DEFAULT 0,
  MODIFY COLUMN duration_unit        ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NOT NULL DEFAULT 'DAY',
  MODIFY COLUMN duration_seconds     DOUBLE NOT NULL DEFAULT 0;


-- ===========================================================================
-- 6. metric_result — the unit a value is expressed over.
--
-- Nullable and not backfilled, because null is the right answer for every row
-- that exists: most of the suite is dimensionless (a fill rate, a
-- centrality, a count), and guessing a unit for the rest would be inventing
-- provenance the old schema never captured. Calculators set it going forward
-- for the metrics that are time-valued.
-- ===========================================================================

ALTER TABLE metric_result
  ADD COLUMN display_unit ENUM('SECOND','MINUTE','HOUR','DAY','WEEK','MONTH','YEAR') NULL AFTER ci_high;


-- ===========================================================================
-- 7. Audit trail — what each old value was, and what it became.
--
-- Written while both generations of column still exist, because after step 9
-- the old numbers are gone and the backfill can no longer be checked against
-- anything. One narrow table, discriminated by which column it records.
--
-- db/verify_v3_backfill.sql queries this and ends with the DROP TABLE to run
-- once the results satisfy you. Nothing in the application reads it.
-- ===========================================================================

CREATE TABLE v3_time_units_audit (
  quantity       VARCHAR(40)  NOT NULL,  -- e.g. 'link.lead_time'
  row_key        VARCHAR(64)  NOT NULL,  -- primary key, composites joined by ':'
  old_value      DOUBLE       NULL,      -- the pre-V3 number (periods, or per-period)
  period_value   DOUBLE       NOT NULL,  -- the period it was interpreted through
  period_seconds DOUBLE       NOT NULL,
  new_value      DOUBLE       NULL,
  new_unit       VARCHAR(8)   NULL,
  new_seconds    DOUBLE       NULL,      -- NULL for rates, which have no derived column
  PRIMARY KEY (quantity, row_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO v3_time_units_audit
      (quantity, row_key, old_value, period_value, period_seconds, new_value, new_unit, new_seconds)
SELECT 'node.capacity', CAST(n.id AS CHAR), n.capacity_per_period,
       w.period_length_value, w.period_length_seconds,
       n.capacity_value, n.capacity_time_unit, NULL
FROM   node n JOIN network w ON w.id = n.network_id;

INSERT INTO v3_time_units_audit
      (quantity, row_key, old_value, period_value, period_seconds, new_value, new_unit, new_seconds)
SELECT 'link.lead_time', CAST(l.id AS CHAR), l.lead_time,
       w.period_length_value, w.period_length_seconds,
       l.lead_time_value, l.lead_time_unit, l.lead_time_seconds
FROM   link l JOIN network w ON w.id = l.network_id;

INSERT INTO v3_time_units_audit
      (quantity, row_key, old_value, period_value, period_seconds, new_value, new_unit, new_seconds)
SELECT 'link.capacity', CAST(l.id AS CHAR), l.capacity_per_period,
       w.period_length_value, w.period_length_seconds,
       l.capacity_value, l.capacity_time_unit, NULL
FROM   link l JOIN network w ON w.id = l.network_id;

INSERT INTO v3_time_units_audit
      (quantity, row_key, old_value, period_value, period_seconds, new_value, new_unit, new_seconds)
SELECT 'node_product.demand', CONCAT(np.node_id, ':', np.product_id), np.demand_per_period,
       w.period_length_value, w.period_length_seconds,
       np.demand_value, np.demand_time_unit, NULL
FROM   node_product np JOIN node n ON n.id = np.node_id JOIN network w ON w.id = n.network_id;

INSERT INTO v3_time_units_audit
      (quantity, row_key, old_value, period_value, period_seconds, new_value, new_unit, new_seconds)
SELECT 'node_product.holding_cost', CONCAT(np.node_id, ':', np.product_id), np.holding_cost,
       w.period_length_value, w.period_length_seconds,
       np.holding_cost_value, np.holding_cost_time_unit, NULL
FROM   node_product np JOIN node n ON n.id = np.node_id JOIN network w ON w.id = n.network_id;

INSERT INTO v3_time_units_audit
      (quantity, row_key, old_value, period_value, period_seconds, new_value, new_unit, new_seconds)
SELECT 'disruption_event.start_offset', CAST(e.id AS CHAR), e.start_period,
       COALESCE(p.period_value, 1), COALESCE(p.period_seconds, 86400),
       e.start_offset_value, e.start_offset_unit, e.start_offset_seconds
FROM   disruption_event e LEFT JOIN v3_scenario_period p ON p.scenario_id = e.scenario_id;

INSERT INTO v3_time_units_audit
      (quantity, row_key, old_value, period_value, period_seconds, new_value, new_unit, new_seconds)
SELECT 'disruption_event.duration', CAST(e.id AS CHAR), e.duration,
       COALESCE(p.period_value, 1), COALESCE(p.period_seconds, 86400),
       e.duration_value, e.duration_unit, e.duration_seconds
FROM   disruption_event e LEFT JOIN v3_scenario_period p ON p.scenario_id = e.scenario_id;

DROP TEMPORARY TABLE v3_scenario_period;


-- ===========================================================================
-- 8. Constraints and indexes over the superseded columns.
--
-- These must go before the columns themselves. MySQL 8 refuses to drop a column
-- a CHECK constraint still references, and an index that merely loses one of
-- its columns is silently rewritten into a different index.
-- ===========================================================================

-- The index, on the derived second-counts rather than on the stated
-- values: the timeline editor reads a scenario's events in time order
-- and the engine asks which are in flight at a given moment, and
-- neither ordering is correct over the stated value once two events in one
-- scenario are written in different units.
--
-- CREATED BEFORE ix_event_scenario IS DROPPED, and that order is load-bearing.
-- ix_event_scenario is (scenario_id, start_period): scenario_id is its leftmost
-- column, so when V2 created the table InnoDB adopted it as the index backing
-- fk_event_scenario instead of building its own. Dropping it while it is the
-- only such index fails with errno 1553, "needed in a foreign key constraint".
-- ix_event_window also leads with scenario_id, so once it exists the foreign
-- key has somewhere else to live and the old index becomes droppable.
CREATE INDEX ix_event_window
  ON disruption_event (scenario_id, start_offset_seconds, duration_seconds);

ALTER TABLE node             DROP CHECK ck_node_capacity;
ALTER TABLE link             DROP CHECK ck_link_capacity;
ALTER TABLE link             DROP CHECK ck_link_lead_time;
ALTER TABLE node_product     DROP CHECK ck_node_product_demand;
ALTER TABLE disruption_event DROP CHECK ck_event_window;

-- Superseded by ix_event_window above; its second column is about to go.
ALTER TABLE disruption_event DROP INDEX ix_event_scenario;


-- ===========================================================================
-- 9. Drop the superseded columns.
--
-- Last, and only now that every replacement is populated and audited.
-- ===========================================================================

ALTER TABLE node             DROP COLUMN capacity_per_period;
ALTER TABLE link             DROP COLUMN lead_time,
                             DROP COLUMN capacity_per_period;
ALTER TABLE node_product     DROP COLUMN demand_per_period,
                             DROP COLUMN holding_cost;
ALTER TABLE disruption_event DROP COLUMN start_period,
                             DROP COLUMN duration;


-- ===========================================================================
-- 10. Constraints and indexes over the new columns.
--
-- The CHECKs restate what their dropped predecessors guaranteed, in the new
-- vocabulary. Durations may be zero — a same-period lead time, an event that
-- begins at once — but never negative; an event still has to last a positive
-- amount of time, which is what ck_event_window guaranteed as duration >= 1.
-- ===========================================================================

ALTER TABLE node
  ADD CONSTRAINT ck_node_capacity CHECK (capacity_value IS NULL OR capacity_value >= 0),
  ADD CONSTRAINT ck_node_processing_time CHECK (processing_time_value >= 0 AND processing_time_seconds >= 0);

ALTER TABLE link
  ADD CONSTRAINT ck_link_capacity  CHECK (capacity_value IS NULL OR capacity_value >= 0),
  ADD CONSTRAINT ck_link_lead_time CHECK (lead_time_value >= 0 AND lead_time_seconds >= 0);

ALTER TABLE node_product
  ADD CONSTRAINT ck_node_product_demand CHECK (demand_value >= 0);

ALTER TABLE disruption_event
  ADD CONSTRAINT ck_event_window CHECK (start_offset_value >= 0 AND start_offset_seconds >= 0
                                        AND duration_value > 0 AND duration_seconds > 0);

-- ix_event_window is not created here: it had to exist before step 8 could drop
-- ix_event_scenario out from under the foreign key. See the note there.
