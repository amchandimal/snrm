-- ============================================================================
-- V2__domain.sql — the entity–relationship model.
--
-- Conventions throughout: MySQL ENUM columns for closed vocabularies,
-- unique keys on the natural identity of each row, and composite indexes laid
-- out along the query paths the application actually uses (the REST endpoints,
-- the network editor, the comparison view). Tables are created parent-first
-- so foreign keys resolve in one pass.
--
-- Immutability is NOT expressed here. A network frozen by a
-- simulation run is a workflow rule with a remedy — fork a variant — so it
-- lives in com.snrm.network.NetworkMutationGuard, which can say so to the
-- caller. A trigger could only reject, opaquely. Deletes therefore cascade
-- along ownership edges (project -> network -> node/link, run -> results):
-- the schema models what belongs to what, and the service layer decides what
-- is allowed.
--
-- Every entity carries created_at / updated_at, written by Spring Data JPA
-- auditing (com.snrm.common.AuditableEntity). The DEFAULT CURRENT_TIMESTAMP(6)
-- is there so hand-written SQL fixtures insert without naming them; there is
-- deliberately no ON UPDATE clause, because the application owns updated_at.
--
-- Charset/collation match db/bootstrap.sql so node and product names can hold
-- any Unicode.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- project — top-level container for one modelling exercise.
--
-- owner_id has no foreign key on purpose: Phase 1 is a single research user
-- and there is no user table yet. It is modelled now so that
-- project-level ownership checks are a filter later, not a migration.
-- ---------------------------------------------------------------------------
CREATE TABLE project (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  name        VARCHAR(160) NOT NULL,
  owner_id    BIGINT       NOT NULL,
  created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_project (owner_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- network — one configuration of the supply network.
--
-- version is the variant generation within (project, name), not an optimistic
-- lock: the baseline is 1 and every fork takes the next number, which is what
-- uq_network keys on. is_baseline marks the network the comparison view
-- measures the others against.
-- ---------------------------------------------------------------------------
CREATE TABLE network (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  project_id  BIGINT       NOT NULL,
  name        VARCHAR(160) NOT NULL,
  version     INT          NOT NULL DEFAULT 1,
  is_baseline BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_network_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
  CONSTRAINT ck_network_version CHECK (version >= 1),
  UNIQUE KEY uq_network (project_id, name, version),
  -- "the networks of this project", and "which one is the baseline".
  KEY ix_network_project (project_id, is_baseline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- node — a facility in the network (NODE, representative DDL).
--
-- pos_x / pos_y are canvas coordinates, not geography: the editor writes them
-- on drop and on drag so a manual layout survives a reload, and auto-layout
-- only fills them where they are absent. lat / lng are the separate
-- geographic pair.
--
-- region is the tag REGION-scoped disruption events resolve through, which is
-- how correlated geographic disruptions are expressed.
--
-- The cost and probability columns could have been `DOUBLE DEFAULT 0`; they
-- are NOT NULL here so the metric and simulation engines never have to reason
-- about a null cost. capacity_per_period stays nullable, where null carries the
-- distinct meaning "unconstrained".
-- ---------------------------------------------------------------------------
CREATE TABLE node (
  id                  BIGINT      NOT NULL AUTO_INCREMENT,
  network_id          BIGINT      NOT NULL,
  name                VARCHAR(120) NOT NULL,
  type                ENUM('SUPPLIER','PLANT','DC','CUSTOMER') NOT NULL,
  capacity_per_period DOUBLE      NULL,
  fixed_cost          DOUBLE      NOT NULL DEFAULT 0,
  var_cost            DOUBLE      NOT NULL DEFAULT 0,
  failure_prob        DOUBLE      NOT NULL DEFAULT 0,
  region              VARCHAR(60) NULL,
  lat                 DOUBLE      NULL,
  lng                 DOUBLE      NULL,
  pos_x               DOUBLE      NULL,   -- canvas coordinates (editor layout)
  pos_y               DOUBLE      NULL,   -- canvas coordinates (editor layout)
  created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_node_network FOREIGN KEY (network_id) REFERENCES network (id) ON DELETE CASCADE,
  CONSTRAINT ck_node_failure_prob CHECK (failure_prob BETWEEN 0 AND 1),
  CONSTRAINT ck_node_capacity CHECK (capacity_per_period IS NULL OR capacity_per_period >= 0),
  -- Unique per network; also what lets the import resolve links.source/target by name.
  UNIQUE KEY uq_node (network_id, name),
  -- "the customers of this network" — network-level validation, echelon layout.
  KEY ix_node_network_type (network_id, type),
  -- REGION-scoped disruption events resolving to node sets.
  KEY ix_node_network_region (network_id, region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- link — a directed transport arc.
--
-- lead_time is in periods and delays arrival of flow, modelled as pipeline
-- inventory. Self-loops and duplicate pairs are rejected at the gesture level
-- and during import; uq_link and ck_link_no_self_loop are
-- the backstop.
-- ---------------------------------------------------------------------------
CREATE TABLE link (
  id                  BIGINT      NOT NULL AUTO_INCREMENT,
  network_id          BIGINT      NOT NULL,
  source_node_id      BIGINT      NOT NULL,
  target_node_id      BIGINT      NOT NULL,
  lead_time           INT         NOT NULL DEFAULT 0,
  capacity_per_period DOUBLE      NULL,
  unit_cost           DOUBLE      NOT NULL DEFAULT 0,
  failure_prob        DOUBLE      NOT NULL DEFAULT 0,
  created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_link_network FOREIGN KEY (network_id) REFERENCES network (id) ON DELETE CASCADE,
  CONSTRAINT fk_link_source  FOREIGN KEY (source_node_id) REFERENCES node (id) ON DELETE CASCADE,
  CONSTRAINT fk_link_target  FOREIGN KEY (target_node_id) REFERENCES node (id) ON DELETE CASCADE,
  CONSTRAINT ck_link_no_self_loop CHECK (source_node_id <> target_node_id),
  CONSTRAINT ck_link_failure_prob CHECK (failure_prob BETWEEN 0 AND 1),
  CONSTRAINT ck_link_lead_time    CHECK (lead_time >= 0),
  CONSTRAINT ck_link_capacity     CHECK (capacity_per_period IS NULL OR capacity_per_period >= 0),
  UNIQUE KEY uq_link (network_id, source_node_id, target_node_id),
  -- Adjacency in both directions: snapshot building, and the dependency list
  -- shown before an editor delete.
  KEY ix_link_source (source_node_id),
  KEY ix_link_target (target_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- product — a product flowing through the network.
--
-- Project-scoped, not network-scoped: a configuration variant is a different
-- structure over the same catalogue, so forking reuses these rows.
-- unit_value weights unserved demand for the economic metrics.
-- ---------------------------------------------------------------------------
CREATE TABLE product (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  project_id  BIGINT       NOT NULL,
  name        VARCHAR(120) NOT NULL,
  unit_value  DOUBLE       NOT NULL DEFAULT 0,
  created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_product_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
  CONSTRAINT ck_product_unit_value CHECK (unit_value >= 0),
  UNIQUE KEY uq_product (project_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- node_product — per-node, per-product parameters (NODE_PRODUCT).
--
-- The ER model gives this table no surrogate id, so the natural (node, product)
-- pair is the primary key. demand_per_period applies to CUSTOMER nodes; the
-- inventory fields to supply-side nodes, where stock acts as an extra supply
-- source in the period after it is stocked.
-- ---------------------------------------------------------------------------
CREATE TABLE node_product (
  node_id           BIGINT      NOT NULL,
  product_id        BIGINT      NOT NULL,
  demand_per_period DOUBLE      NOT NULL DEFAULT 0,
  initial_inventory DOUBLE      NOT NULL DEFAULT 0,
  safety_stock      DOUBLE      NOT NULL DEFAULT 0,
  holding_cost      DOUBLE      NOT NULL DEFAULT 0,
  created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (node_id, product_id),
  CONSTRAINT fk_node_product_node    FOREIGN KEY (node_id) REFERENCES node (id) ON DELETE CASCADE,
  CONSTRAINT fk_node_product_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE,
  CONSTRAINT ck_node_product_demand    CHECK (demand_per_period >= 0),
  CONSTRAINT ck_node_product_inventory CHECK (initial_inventory >= 0 AND safety_stock >= 0),
  -- "every node carrying this product", the reverse of the primary key.
  KEY ix_node_product_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- disruption_scenario — a named event set plus its Monte Carlo settings
-- (DISRUPTION_SCENARIO).
--
-- Project-scoped so the same scenario can be replayed against every variant,
-- which is what makes the comparison view meaningful. num_replications
-- defaults to the 100. A null seed means "draw one per run"; the seed
-- actually used is recorded in simulation_run.params_json either way.
-- ---------------------------------------------------------------------------
CREATE TABLE disruption_scenario (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  project_id       BIGINT       NOT NULL,
  name             VARCHAR(160) NOT NULL,
  num_replications INT          NOT NULL DEFAULT 100,
  seed             BIGINT       NULL,
  created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_scenario_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
  CONSTRAINT ck_scenario_replications CHECK (num_replications >= 1),
  UNIQUE KEY uq_scenario (project_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- disruption_event — one disruption within a scenario.
--
-- severity is a capacity-availability multiplier reduction in [0,1]; 1.0 takes
-- the target fully offline for the duration. probability is the chance the
-- event occurs at all in a replication, so a scenario describes a distribution
-- of futures. recovery_profile is parameterised by duration.
--
-- target_id is a polymorphic reference — node.id or link.id per target_type —
-- so no foreign key can express it; dangling references are caught by scenario
-- validation. ix_event_target is what makes "which events target this node?"
-- cheap, which the editor needs before a delete.
--
-- OPEN POINT: the ER model types the target as `bigint
-- target_id`, but a REGION event has to name a region, and node.region is a
-- VARCHAR(60) tag. The column follows the ER model as written and stays NULL
-- for REGION rows; naming the region needs a target_region column or a region
-- lookup table, which is a model change rather than a migration decision.
-- ck_event_target therefore requires an id for NODE and LINK only.
-- ---------------------------------------------------------------------------
CREATE TABLE disruption_event (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  scenario_id      BIGINT      NOT NULL,
  target_type      ENUM('NODE','LINK','REGION') NOT NULL,
  target_id        BIGINT      NULL,
  start_period     INT         NOT NULL,
  duration         INT         NOT NULL,
  severity         DOUBLE      NOT NULL,
  recovery_profile ENUM('STEP','LINEAR','EXPONENTIAL') NOT NULL DEFAULT 'STEP',
  probability      DOUBLE      NOT NULL DEFAULT 1,
  created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_event_scenario FOREIGN KEY (scenario_id) REFERENCES disruption_scenario (id) ON DELETE CASCADE,
  CONSTRAINT ck_event_severity    CHECK (severity BETWEEN 0 AND 1),
  CONSTRAINT ck_event_probability CHECK (probability BETWEEN 0 AND 1),
  CONSTRAINT ck_event_window      CHECK (start_period >= 0 AND duration >= 1),
  CONSTRAINT ck_event_target      CHECK (target_type = 'REGION' OR target_id IS NOT NULL),
  -- The timeline editor reads a scenario's events in period order.
  KEY ix_event_scenario (scenario_id, start_period),
  -- "which events target this node/link?" — the delete confirmation.
  KEY ix_event_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- simulation_run — one evaluation of a network against a scenario
-- (SIMULATION_RUN).
--
-- A run is what freezes a network. ix_run_network_status answers the
-- guard's question — "does a run in a locking state reference this network?" —
-- from the index alone, so the check can sit in front of every mutation.
--
-- params_json holds the fully resolved parameter set including the seed drawn,
-- which is why the ER model gives the run no separate seed column: the scenario
-- carries the configured default, the run records what was used.
-- ---------------------------------------------------------------------------
CREATE TABLE simulation_run (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  network_id  BIGINT      NOT NULL,
  scenario_id BIGINT      NOT NULL,
  status      ENUM('QUEUED','RUNNING','DONE','FAILED','CANCELLED') NOT NULL DEFAULT 'QUEUED',
  started_at  DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  params_json JSON        NOT NULL,
  created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_run_network  FOREIGN KEY (network_id) REFERENCES network (id) ON DELETE CASCADE,
  CONSTRAINT fk_run_scenario FOREIGN KEY (scenario_id) REFERENCES disruption_scenario (id) ON DELETE CASCADE,
  -- The immutability guard's query path.
  KEY ix_run_network_status (network_id, status),
  KEY ix_run_scenario (scenario_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- metric_result — one computed metric value (METRIC_RESULT).
--
-- Deliberately narrow: metric_code is an opaque string, so adding a metric to
-- the suite is a new MetricCalculator bean and no schema change. run_id is NULL
-- for topological metrics, which belong to the network rather than to any run.
-- ci_low / ci_high carry the 95% interval across replications where one is
-- meaningful.
--
-- ix_mr is the index, verbatim. ix_mr_run covers the other direction
-- — a results dashboard reading one run's suite.
--
-- No unique key on (network, run, metric_code, scope, scope_id): MySQL treats
-- NULLs as distinct, so it would not constrain the topological rows anyway. The
-- metric registry replaces a network's topological rows wholesale on recompute.
-- ---------------------------------------------------------------------------
CREATE TABLE metric_result (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  network_id  BIGINT      NOT NULL,
  run_id      BIGINT      NULL,
  metric_code VARCHAR(40) NOT NULL,
  scope       ENUM('NETWORK','NODE','LINK') NOT NULL DEFAULT 'NETWORK',
  scope_id    BIGINT      NULL,
  value       DOUBLE      NOT NULL,
  ci_low      DOUBLE      NULL,
  ci_high     DOUBLE      NULL,
  created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_mr_network FOREIGN KEY (network_id) REFERENCES network (id) ON DELETE CASCADE,
  CONSTRAINT fk_mr_run     FOREIGN KEY (run_id) REFERENCES simulation_run (id) ON DELETE CASCADE,
  CONSTRAINT ck_mr_ci CHECK (ci_low IS NULL OR ci_high IS NULL OR ci_low <= ci_high),
  KEY ix_mr (network_id, run_id, metric_code),
  KEY ix_mr_run (run_id, metric_code, scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- run_timeseries — per-period aggregates of a run, averaged across
-- replications (RUN_TIMESERIES).
--
-- These rows are what let the performance curve and the resilience triangle be
-- drawn without recomputation. The ER model gives the table no surrogate id and
-- (run, period) is also the access path — one run's periods in order — so the
-- natural key is the primary key and the scan is clustered.
-- ---------------------------------------------------------------------------
CREATE TABLE run_timeseries (
  run_id        BIGINT      NOT NULL,
  period        INT         NOT NULL,
  served_demand DOUBLE      NOT NULL DEFAULT 0,
  total_demand  DOUBLE      NOT NULL DEFAULT 0,
  cost          DOUBLE      NOT NULL DEFAULT 0,
  created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (run_id, period),
  CONSTRAINT fk_timeseries_run FOREIGN KEY (run_id) REFERENCES simulation_run (id) ON DELETE CASCADE,
  CONSTRAINT ck_timeseries_period CHECK (period >= 0),
  CONSTRAINT ck_timeseries_demand CHECK (served_demand >= 0 AND total_demand >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- configuration_variant — provenance of a derived network
-- (CONFIGURATION_VARIANT).
--
-- network_id is the ER model's "materialises as" edge, held unique so a network
-- has at most one variant record; base_network_id is what it was forked from. A
-- baseline network has no row here at all.
--
-- lever_changes_json is the structured diff from the base — added backup
-- supplier, +20% capacity at node 12 — which is what makes lever-level
-- attribution of metric improvements possible.
--
-- generated_by: MANUAL is a user fork from the editor, SEARCH a
-- candidate persisted by the Phase 2 configuration engine. The column is an
-- enum with no other members; these are the only two paths that create a
-- network.
-- ---------------------------------------------------------------------------
CREATE TABLE configuration_variant (
  id                 BIGINT      NOT NULL AUTO_INCREMENT,
  project_id         BIGINT      NOT NULL,
  base_network_id    BIGINT      NOT NULL,
  network_id         BIGINT      NOT NULL,
  generated_by       ENUM('MANUAL','SEARCH') NOT NULL,
  lever_changes_json JSON        NULL,
  created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_variant_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
  CONSTRAINT fk_variant_base    FOREIGN KEY (base_network_id) REFERENCES network (id) ON DELETE CASCADE,
  CONSTRAINT fk_variant_network FOREIGN KEY (network_id) REFERENCES network (id) ON DELETE CASCADE,
  CONSTRAINT ck_variant_not_self CHECK (base_network_id <> network_id),
  -- The ER model's 1:1 "materialises as".
  UNIQUE KEY uq_variant_network (network_id),
  KEY ix_variant_project (project_id, generated_by),
  -- The provenance tree of a baseline: everything forked from it.
  KEY ix_variant_base (base_network_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
