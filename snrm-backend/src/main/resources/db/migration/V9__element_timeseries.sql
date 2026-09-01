-- ============================================================================
-- V9__element_timeseries.sql — per-element time series: what each node and each
-- link did in each period, so a playback view can draw the network moving
-- rather than only its aggregate curve (FR-18).
--
-- THE OPEN POINT THIS CLOSES
--
-- RUN_TIMESERIES answers "how did the NETWORK perform in period t" — served
-- demand, total demand, cost, and (V6) the undisrupted baseline beside them.
-- That is exactly one curve per run, and for the aggregate view it is enough.
--
-- It is not enough for the playback view, which renders inventory bars at each
-- node, ribbons on each link, and a disruption overlay on the elements the
-- events actually touched; each of those is a per-element quantity that
-- RUN_TIMESERIES aggregated away. The alternatives were both worse:
--
--   * re-run the simulation when the view opens — which the "without
--     recomputation" rule forbids, and which cannot reproduce a run whose
--     network has since been forked;
--   * ship the raw replication traces — H × N × R numbers, unaggregated, which
--     is the one thing the replication-mean convention exists to avoid.
--
-- So the same convention RUN_TIMESERIES already uses is applied one level down.
--
-- REPLICATION-MEAN SEMANTICS
--
-- Every DOUBLE below is a MEAN OVER THE DISRUPTED REPLICATIONS, exactly as
-- RUN_TIMESERIES stores means rather than sums: a client comparing
-- two runs with different replication counts must be comparing like with like.
-- The engine folds each finished replication into per-period sums and divides
-- once at the end, so the accumulator is O(H × (N + E)) whatever N is
-- (ElementAccumulator).
--
-- Two columns are means over a SUBSET of the replications, and are NULL rather
-- than 0 when that subset is empty:
--
--   * inbound_lead — the dispatch-weighted transport lead of everything sent
--     TOWARD a node this period. A period in which nothing was dispatched
--     toward it has no such mean; 0 would claim instantaneous delivery, which
--     is a different and false statement. Transport lead only: a node's own
--     processing dwell is not part of how long its inbound material travelled.
--   * utilisation — flow ÷ available capacity. NULL where the link declares no
--     capacity at all ("an unconstrained element cannot be partly disrupted" —
--     FlowAllocator.adjusted), and NULL where an event has taken the available
--     capacity to zero, because 0/0 is not 0 and a dark link is not an idle one.
--
-- This is the same rule the metric suite already follows: an absent row is
-- unmeasured, never zero.
--
-- THE BASELINE-RUN COPY-IN CONVENTION
--
-- baseline_on_hand, baseline_served and baseline_flow carry the same period of
-- the undisrupted baseline replication set — the mirror of what V6
-- did for served demand and cost, and what makes a per-element resilience
-- triangle drawable without a second query.
--
-- On the BASELINE RUN of FR-17 — no scenario, so MonteCarloRunner skips the
-- pairing and the baseline set is empty — these three columns are COPIED FROM
-- THE RUN'S OWN DISRUPTED SERIES. That is definitionally true rather than a
-- stand-in: the paired set, had it run, would have drawn identically, and it
-- keeps every reader of the two curves working with a triangle of zero area.
-- V6's aggregate() takes exactly this convention for exactly this reason.
--
-- WHY NO FOREIGN KEY ON node_id / link_id
--
-- Only run_id carries one. A results row is a statement about a run, and it
-- must survive as long as the run does: a run freezes its network, so
-- the elements cannot be edited away underneath it, and when the network does
-- go, fk_node_ts_run takes these rows with the run through simulation_run's own
-- cascade. Adding node(id) and link(id) foreign keys would buy nothing and
-- would make a per-element series a reason a node cannot be deleted — which is
-- a rule about the editor, decided in NodeService, not one for a results table
-- to invent.
--
-- WHY (run_id, element_id, period) IS THE PRIMARY KEY
--
-- It is also the access path, as it is in RUN_TIMESERIES: one element's whole
-- horizon in order is a clustered scan of a primary-key prefix, which is what
-- GET /simulations/{runId}/timeseries/nodes/{nodeId} does. Element-major rather
-- than period-major for the same reason — the drill-down is by element.
--
-- ending_inventory AND in_pipeline ON run_timeseries
--
-- Both were already computed on every PeriodTrace and both were being thrown
-- away. They are the network totals the per-element on_hand and in_transit
-- columns sum to, so a client can draw the aggregate stock and pipeline curves
-- without pulling the whole element series — and a reader can check the element
-- series against them, which is what samples/four-echelon-playback/README.md
-- §6.5 does. They are added rather than derived on read because deriving them
-- would require the element rows to exist, and a run recorded with
-- recordElementTimeseries = false has none.
--
-- EXISTING ROWS
--
-- The two new tables start empty. run_timeseries may hold rows from earlier
-- runs, and DEFAULT 0 is what they take: a run that predates this migration
-- recorded no inventory curve, and 0 is the honest arithmetic form of a column
-- that was never written rather than a claim about that run. Such a run also
-- has no element rows at all, which is why the API reports available: false for
-- it rather than an empty series that looks like a network that did nothing.
--
-- Storage engine and charset are the schema defaults (InnoDB, utf8mb4) — the
-- same ones V2__domain.sql states explicitly.
-- ============================================================================

CREATE TABLE run_node_timeseries (
  run_id BIGINT NOT NULL, node_id BIGINT NOT NULL, period INT NOT NULL,
  on_hand DOUBLE NOT NULL,            -- end-of-period stock, mean over disrupted replications
  in_transit DOUBLE NOT NULL,         -- inbound pipeline at period end
  arrivals DOUBLE NOT NULL,           -- delivered by the pipeline this period (captured before zeroing)
  served DOUBLE NOT NULL,             -- customer nodes; 0 elsewhere
  unserved DOUBLE NOT NULL,
  throughput DOUBLE NOT NULL,         -- production / pass-through
  availability DOUBLE NOT NULL,       -- mean availability multiplier (events x outages)
  inbound_lead DOUBLE NULL,           -- dispatch-weighted inbound lead (periods); NULL = nothing dispatched toward it
  baseline_on_hand DOUBLE NOT NULL,
  baseline_served DOUBLE NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (run_id, node_id, period),
  CONSTRAINT fk_node_ts_run FOREIGN KEY (run_id) REFERENCES simulation_run (id) ON DELETE CASCADE
);

CREATE TABLE run_link_timeseries (
  run_id BIGINT NOT NULL, link_id BIGINT NOT NULL, period INT NOT NULL,
  flow DOUBLE NOT NULL,
  utilisation DOUBLE NULL,            -- flow / (capacity x availability); NULL when uncapped
  availability DOUBLE NOT NULL,
  baseline_flow DOUBLE NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (run_id, link_id, period),
  CONSTRAINT fk_link_ts_run FOREIGN KEY (run_id) REFERENCES simulation_run (id) ON DELETE CASCADE
);

ALTER TABLE run_timeseries
  ADD COLUMN ending_inventory DOUBLE NOT NULL DEFAULT 0 AFTER baseline_cost,
  ADD COLUMN in_pipeline      DOUBLE NOT NULL DEFAULT 0 AFTER ending_inventory;
