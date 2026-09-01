-- ============================================================================
-- V4__time_units_canonical.sql — bring the time-unit schema into exact
-- agreement with the canonical time-unit model.
--
-- V3 delivered the model but deviated from the intended DDL in
-- two places, both found by re-checking the implementation against the model
-- rather than against itself. Neither is a behaviour change; both are the
-- schema saying precisely what the model means.
--
--   1. The derived canonical columns were DOUBLE. Every one of them is
--      `bigint` — for the node column,
--      `processing_time_seconds BIGINT DEFAULT 0`. A canonical form exists to be
--      compared, sorted and indexed, and those are all sharper over an integer:
--      equality is exact rather than within an epsilon, and a range scan on an
--      integral key needs no tolerance. The second is the finest unit the model
--      has, so a whole second is the natural grain — there is no
--      unit whose values it cannot represent.
--
--   2. `ix_node_proc` was missing. The index the model calls for is
--      KEY ix_node_proc (network_id, processing_time_seconds). It is the
--      "which nodes in this network dwell longest" query path — what the
--      resolution validation asks when it looks for the longest
--      declared duration (PERIOD_TOO_FINE), and what the editor's warning banner
--      ranks by.
--
-- SAFETY. Rounding is done as an explicit UPDATE before each MODIFY rather than
-- left to MySQL's implicit conversion, so the values that change do so by a
-- statement that says it is changing them, and a strict-mode truncation error
-- cannot abort the migration halfway. Every existing row is already integral —
-- V3 backfilled n periods x period_seconds from whole numbers — so on data
-- migrated from V2 the UPDATEs are no-ops. They earn their place on a database
-- where someone has since entered a fractional duration (0.5 DAY is 43200s and
-- survives; 0.5 SECOND does not).
--
-- The rounding is NEAREST, matching com.snrm.common.DurationAmount, which now
-- rounds the same way when it maintains these columns on persist. The stated
-- (value, unit) pair is untouched, so nothing the user typed is altered and
-- nothing displayed or exported changes — only the canonical form each row is
-- compared through.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. network.period_length_seconds
--
-- Every duration carries a derived canonical column, and the network's period
-- length is explicitly one of them. Same treatment
-- as the rest.
-- ---------------------------------------------------------------------------
UPDATE network SET period_length_seconds = ROUND(period_length_seconds);

ALTER TABLE network
  MODIFY COLUMN period_length_seconds BIGINT NOT NULL DEFAULT 86400;


-- ---------------------------------------------------------------------------
-- 2. node.processing_time_seconds, and the index.
--
-- ck_node_processing_time is dropped and restored around the MODIFY: MySQL will
-- not alter a column a CHECK constraint references (errno 3959), the same rule
-- that shaped V3's ordering.
-- ---------------------------------------------------------------------------
UPDATE node SET processing_time_seconds = ROUND(processing_time_seconds);

ALTER TABLE node DROP CHECK ck_node_processing_time;

ALTER TABLE node
  MODIFY COLUMN processing_time_seconds BIGINT NOT NULL DEFAULT 0;

ALTER TABLE node
  ADD CONSTRAINT ck_node_processing_time CHECK (processing_time_value >= 0 AND processing_time_seconds >= 0);

-- Leading with network_id because every question asked of it
-- is asked within one network — the validation sweep and the
-- editor banner both scope to the network being edited — and the
-- canonical column second because that is what they order and range over.
CREATE INDEX ix_node_proc ON node (network_id, processing_time_seconds);


-- ---------------------------------------------------------------------------
-- 3. link.lead_time_seconds
-- ---------------------------------------------------------------------------
UPDATE link SET lead_time_seconds = ROUND(lead_time_seconds);

ALTER TABLE link DROP CHECK ck_link_lead_time;

ALTER TABLE link
  MODIFY COLUMN lead_time_seconds BIGINT NOT NULL DEFAULT 0;

ALTER TABLE link
  ADD CONSTRAINT ck_link_lead_time CHECK (lead_time_value >= 0 AND lead_time_seconds >= 0);


-- ---------------------------------------------------------------------------
-- 4. disruption_event.start_offset_seconds and duration_seconds.
--
-- ix_event_window is (scenario_id, start_offset_seconds, duration_seconds) and
-- both of its trailing columns change type here. MySQL rebuilds an index across
-- a MODIFY of one of its columns, so the index survives — but it also backs
-- fk_event_scenario, exactly as ix_event_scenario did before V3 dropped it, so
-- it must not be dropped and recreated around the MODIFY. It is left alone
-- deliberately; check 4 of db/verify_v4_canonical.sql confirms it came through.
-- ---------------------------------------------------------------------------
UPDATE disruption_event
SET    start_offset_seconds = ROUND(start_offset_seconds),
       duration_seconds     = ROUND(duration_seconds);

ALTER TABLE disruption_event DROP CHECK ck_event_window;

ALTER TABLE disruption_event
  MODIFY COLUMN start_offset_seconds BIGINT NOT NULL DEFAULT 0,
  MODIFY COLUMN duration_seconds     BIGINT NOT NULL DEFAULT 0;

ALTER TABLE disruption_event
  ADD CONSTRAINT ck_event_window CHECK (start_offset_value >= 0 AND start_offset_seconds >= 0
                                        AND duration_value > 0 AND duration_seconds > 0);
