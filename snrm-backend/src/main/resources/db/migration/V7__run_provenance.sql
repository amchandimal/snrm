-- ============================================================================
-- V7__run_provenance.sql — let a restored simulation run say that it was
-- restored, and name the run it was restored from.
--
-- WHAT THIS IS FOR
--
-- Reproducibility requires a project export/import (JSON bundle) so an entire
-- experiment is archivable alongside the thesis.
--
-- An archive that restores runs creates a problem it must also solve. Once the
-- bundle is imported, simulation_run holds rows this instance never computed:
-- the numbers in metric_result and run_timeseries beside them were produced
-- somewhere else, by whatever engine that instance was running. Without a mark,
-- a restored result is indistinguishable from one this installation produced —
-- and the comparison view would happily put the two in adjacent
-- columns, which is the precise failure the archive exists to prevent.
--
-- WHY A COLUMN AND NOT A STATUS
--
-- The obvious alternative is a sixth simulation_run.status value, IMPORTED.
-- It was rejected because status is the lifecycle
-- (QUEUED → RUNNING → DONE) and a restored run genuinely *is* DONE: its results
-- are complete and final. Every query that matters filters on that literal —
-- ComparisonService takes "the most recent DONE run" of each network,
-- SimulationStatus.networkLocking() freezes a network against it — so a sixth
-- value would silently drop restored runs out of the comparison view and unfreeze
-- the networks they were computed against. Provenance and lifecycle are two
-- questions, and one column cannot answer both.
--
-- WHY NOT A FLAG INSIDE params_json
--
-- Because params_json is copied out of the bundle verbatim: it is the replay
-- instruction, and the archive's whole claim is that it reproduces
-- what the source instance recorded. Writing an "imported" marker into it would
-- edit the evidence. It is also a JSON column the persistence layer treats as
-- opaque text (see SimulationRun), so filtering on it needs JSON functions where
-- a nullable column needs an IS NULL.
--
-- WHY THE ENGINE VERSION IS NOT HERE
--
-- params_json already carries it. SimulationParams.engineVersion exists because
-- "a stored parameter set replays exactly only against the engine that wrote it",
-- and the bundle copies params_json across unchanged — so a run restored from an
-- instance running engine 1.0 into an instance running 2.0 still reports 1.0,
-- which is the true answer. A duplicate column here could only disagree with it.
-- The bundle records the exporting instance's version once, in its manifest, and
-- the import reports the mismatch; see com.snrm.archive.
--
-- EXISTING ROWS
--
-- Every existing run was computed by this instance, which is exactly what
-- imported_at IS NULL means. The columns are nullable with no default and no
-- backfill is needed or wanted.
-- ============================================================================

ALTER TABLE simulation_run
  ADD COLUMN imported_at   DATETIME(6) NULL AFTER finished_at,
  ADD COLUMN source_run_id BIGINT      NULL AFTER imported_at;

-- Deliberately not a foreign key: source_run_id names a row in a *different*
-- database — the instance that computed the run — in the same spirit as
-- disruption_event.target_id, which references node or link and can reference
-- neither. It is provenance for a reader, not a join.

-- The comparison view's question is "did this installation compute this?", asked
-- once per candidate run. Indexed with network_id because that is how the
-- question is always reached: the runs of a network, of which the imported ones
-- are a subset.
CREATE INDEX ix_run_imported ON simulation_run (network_id, imported_at);
