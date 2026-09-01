-- ============================================================================
-- V8__baseline_run.sql — let a simulation run apply no scenario at all: the
-- undisrupted baseline run of FR-17.
--
-- WHAT THIS IS FOR
--
-- Until now SimulationRequest.scenarioId was @NotNull and simulation_run's
-- scenario_id column NOT NULL, so there was no way to ask the one question every
-- disrupted result is read against: how does this configuration perform when
-- nothing goes wrong? That baseline is the first thing worth running on a
-- network just built or imported, and the comparator the resilience triangle is
-- drawn from. scenarioId is therefore nullable on POST /simulations; a NULL
-- here is what records that choice.
--
-- WHY NULL AND NOT A SENTINEL SCENARIO
--
-- The obvious alternative is a reserved "no disruption" scenario row per
-- project, referenced by every baseline run. It was rejected because such a row
-- would be an editable entity: a researcher could rename it, add events to it,
-- or delete it — each of which silently changes what every existing baseline
-- run *means*. A NULL cannot be edited into meaning something else. It also
-- keeps the ownership rule honest: a scenario is a researcher's artefact, and
-- the engine inventing one would put a row in their list they never created.
--
-- WHAT A NULL MEANS DOWNSTREAM
--
-- Exactly one thing: the run executed its N replications with no ScenarioPlan,
-- so no pairing was run (the undisrupted set would have been identical to the
-- disrupted one — the pairing exists to isolate a disruption's effect,
-- and there is none to isolate). The disruption-relative metrics — TTR,
-- LOSS_AREA, DISRUPTION_COST_DELTA, RESILIENCE_INDEX — produce no rows for such
-- a run, which readers must already treat as "unmeasured", never zero
-- (ComparisonNoteDto.PARTIAL_SUITE states this contract).
--
-- THE CASCADE THAT NO LONGER APPLIES
--
-- fk_run_scenario is ON DELETE CASCADE: deleting a scenario deletes its runs.
-- A baseline run references no scenario, so no scenario deletion can ever take
-- it with it — the desired property, since the baseline is the one result that
-- depends on nothing but the network.
--
-- EXISTING ROWS
--
-- Every existing run applied a real scenario and keeps its id. No backfill.
-- ============================================================================

-- MySQL requires the foreign key out of the way while the column's nullability
-- changes; it is re-created identically below.
ALTER TABLE simulation_run DROP FOREIGN KEY fk_run_scenario;

ALTER TABLE simulation_run
  MODIFY COLUMN scenario_id BIGINT NULL;

ALTER TABLE simulation_run
  ADD CONSTRAINT fk_run_scenario FOREIGN KEY (scenario_id)
      REFERENCES disruption_scenario (id) ON DELETE CASCADE;
