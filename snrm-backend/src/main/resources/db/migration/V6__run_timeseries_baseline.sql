-- ============================================================================
-- V6__run_timeseries_baseline.sql — carry the undisrupted baseline curve beside
-- the disrupted one, so the resilience triangle can be drawn from stored data.
--
-- THE OPEN POINT THIS CLOSES
--
-- RUN_TIMESERIES is meant to
--
--      store per-period aggregates (served demand, total demand, cost) as
--      replication averages, powering performance curves and the resilience
--      triangle without recomputation
--
-- and the ER model gives it four columns: run_id, period,
-- served_demand, total_demand, cost. Those four draw the performance curve.
-- They cannot draw the resilience triangle.
--
-- The triangle is the area *between* two curves — the disrupted run and the
-- undisrupted baseline — and that baseline is mandatory:
--
--      Every simulation automatically includes one undisrupted baseline
--      replication set, since TTR, LOSS_AREA, RESILIENCE_INDEX and
--      DISRUPTION_COST_DELTA are all defined relative to baseline performance.
--
-- The ER model predates that requirement and has nowhere to put the second
-- curve. Without it, a client can read LOSS_AREA as a single number but cannot
-- render the shape it is the area of, and the results view would have
-- to re-run the simulation to draw a picture the run already computed — which is
-- precisely what "without recomputation" rules out.
--
-- Two columns is the smallest honest fix. The ER model needs correcting
-- to match; this migration is that amendment.
--
-- WHY TWO COLUMNS AND NOT A SECOND RUN ROW
--
-- The alternative was to persist the baseline set as its own simulation_run and
-- join the two. It was rejected because a baseline is not a run: it has no
-- scenario of its own (fk_run_scenario is NOT NULL), it would appear in
-- GET /networks/{id}/simulations as a result a researcher never asked for, it
-- would freeze the network a second time under the immutability rule, and
-- deleting either half would leave the other meaningless. The baseline is part
-- of the run that produced it, and belongs in its rows.
--
-- WHY NOT A BASELINE total_demand
--
-- Because it would always equal the disrupted run's. The paired replications
-- share their demand realisations by construction — the baseline differs
-- only in that the scenario's events do not occur — so a third column would
-- store a copy of total_demand and invite a client to wonder why they ever
-- differed. Baseline fill rate is baseline_served_demand / total_demand.
--
-- EXISTING ROWS
--
-- None. run_timeseries has never been written: the simulation engine
-- arrives with this release. The defaults below therefore describe the shape of
-- the column rather than a backfill, and NOT NULL is safe.
-- ============================================================================

ALTER TABLE run_timeseries
  ADD COLUMN baseline_served_demand DOUBLE NOT NULL DEFAULT 0 AFTER cost,
  ADD COLUMN baseline_cost          DOUBLE NOT NULL DEFAULT 0 AFTER baseline_served_demand;

-- Mirrors ck_timeseries_demand on the disrupted half: served demand is a
-- quantity and cannot be negative. Cost is deliberately unconstrained in both
-- halves — a network with negative unit costs is a modelling error the importer
-- catches, not something the results table should be the last line of defence
-- against.
ALTER TABLE run_timeseries
  ADD CONSTRAINT ck_timeseries_baseline_demand CHECK (baseline_served_demand >= 0);
