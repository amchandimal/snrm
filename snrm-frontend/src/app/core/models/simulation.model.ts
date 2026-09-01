import { Id, IsoInstant } from './common.model';
import { JobStatus } from './job.model';
import { MetricResult } from './metric.model';
import { Duration } from './time.model';

/**
 * Simulation DTOs - `POST /simulations`, `GET /simulations/{runId}`, `/results`, `/timeseries` and
 * `/timeseries/elements` (FR-06 – FR-08, FR-18).
 *
 * Mirrors the backend's `SimulationRequest`, `SimulationParamsDto`, `SimulationParams`,
 * `SimulationAcceptedDto`, `SimulationRunDto`, `RunTimeseriesDto`, `SimulationResultsDto`,
 * `NodeTimeseriesDto`, `LinkTimeseriesDto` and `ElementTimeseriesDto`.
 */

/** Run status. The same vocabulary as {@link JobStatus} - a run is what a simulation job produces. */
export type SimulationStatus = JobStatus;

/**
 * Per-run overrides on `POST /simulations`. **Every field is optional and omitting one means "not
 * stated"**, not "zero".
 *
 * The scenario already carries a replication count and a seed and the network already
 * carries a horizon; this object is for the run that wants to differ from them - a
 * quick ten-replication look before committing to a hundred, a re-run of a stored seed, a
 * sensitivity check with demand noise switched on. Precedence is request → scenario → default.
 *
 * The horizon is deliberately absent: a run evaluates the network as it stands, and the horizon is
 * part of the network (FR-13).
 */
export interface SimulationParamsRequest {
  /**
   * Disrupted replications. The undisrupted baseline set adds the same number again, so
   * the job executes twice this many.
   */
  readonly replications?: number;
  /** Base RNG seed. Omitted, the scenario's is used; if that is null too, one is drawn and recorded. */
  readonly seed?: number;
  /** Coefficient of variation of multiplicative demand noise. 0 makes demand exactly deterministic. */
  readonly demandNoiseCv?: number;
  /** Maximum whole-period perturbation of each event's start and duration, from [−j, +j]. */
  readonly timingJitterPeriods?: number;
  /** Whether per-period `failureProb` outages apply at all. True by default. */
  readonly includeRandomFailures?: boolean;
  /**
   * Whether the undisrupted baseline set also drops random failures. False by default, so random
   * outages - a property of the network rather than of the scenario - appear in both sets and
   * `DISRUPTION_COST_DELTA` isolates the scenario.
   */
  readonly baselineSuppressesFailures?: boolean;
  /**
   * What a unit of safety-stock shortfall is worth against a unit of unmet customer demand, in
   * [0,1). 0.1 by default; 0 switches replenishment off entirely.
   */
  readonly safetyStockPriority?: number;
  /** Cost of one unit of unmet demand. Omit to price it from the product the customer wanted. */
  readonly unmetDemandPenalty?: number;
  /** Fixed-point resolution of the flow solve: quantities carried to 1/quantum of a unit. */
  readonly quantum?: number;
}

/**
 * The **resolved** parameter set, as stored in `simulation_run.params_json`.
 *
 * Nothing here is null and nothing is a default waiting to be applied - the server filled every gap
 * from the scenario and from its own constants. That is what makes it a replay instruction rather
 * than a copy of a request: re-submitting it reproduces the run exactly, with no reference to what
 * the scenario says today.
 */
export interface SimulationParams {
  readonly replications: number;
  readonly seed: number;
  readonly horizonPeriods: number;
  readonly demandNoiseCv: number;
  readonly timingJitterPeriods: number;
  readonly includeRandomFailures: boolean;
  readonly baselineSuppressesFailures: boolean;
  readonly safetyStockPriority: number;
  /** Null means "priced from the product the customer wanted". */
  readonly unmetDemandPenalty: number | null;
  readonly quantum: number;
  /**
   * Which engine produced this run. A stored parameter set replays exactly only against the engine
   * that wrote it; this is what lets a reader of an old run know when it does not.
   */
  readonly engineVersion: string;
}

/**
 * Body of `POST /simulations`. Answers `202 Accepted` with a {@link SimulationAccepted}.
 *
 * **No scenario is the baseline run of FR-17**: the network evaluated with nothing going wrong -
 * the comparator every disrupted run is read against, and the first thing worth running on a
 * network just built or imported. Such a run executes exactly N replications (the paired set
 * exists to isolate a disruption's effect, and there is none to isolate), and its suite
 * omits the four disruption-relative metrics - absent rows, never zeros.
 */
export interface SimulationRequest {
  readonly networkId: Id;
  /** Omit - or send null - for the undisrupted baseline run of FR-17. */
  readonly scenarioId?: Id | null;
  /** Omit for the scenario's own settings; on a baseline run, for the engine defaults. */
  readonly params?: SimulationParamsRequest;
}

/**
 * The `202` body of `POST /simulations`.
 *
 * Carries `runId` as well as `jobId` because the run row exists by the time this returns - it has
 * to, since creating it is what freezes the network - so the results view can open on a
 * run that is still executing rather than waiting for the job to finish to learn where its answer
 * will be.
 */
export interface SimulationAccepted {
  readonly jobId: string;
  readonly runId: Id;
  /** Always `QUEUED` at this point. */
  readonly status: SimulationStatus;
  /** The fully resolved set, including the seed actually drawn. */
  readonly params: SimulationParams;
  /**
   * Total replications the job will execute: twice the disrupted count when the run includes the
   * paired baseline set, and exactly N for a baseline run (FR-17).
   */
  readonly replications: number;
}

/** One execution of a scenario against a network. Immutable once completed. */
export interface SimulationRun {
  readonly id: Id;
  readonly networkId: Id;
  /** Carried so a results view needs no second fetch. */
  readonly networkName: string;
  /**
   * The scenario applied, or **null for a baseline run** (FR-17). A baseline run's suite carries no
   * TTR, LOSS_AREA, DISRUPTION_COST_DELTA or RESILIENCE_INDEX rows - a view renders them absent,
   * never as zero - and its two curves coincide by definition.
   */
  readonly scenarioId: Id | null;
  /** Null for a baseline run; a client labels it, never invents a scenario name for it. */
  readonly scenarioName: string | null;
  readonly status: SimulationStatus;
  readonly startedAt: IsoInstant | null;
  readonly finishedAt: IsoInstant | null;
  /**
   * The evaluated network's period length.
   *
   * On the run because nothing in the results can be read without it: the performance curve's
   * x-axis is labelled in this unit, and `TTR` is a count of *these* periods, so a bare 14 means
   * fourteen days on one network and fourteen hours on another. The network is
   * frozen while the run is locking, so this clock cannot drift from the one that
   * produced the numbers.
   */
  readonly periodLength: Duration;
  /** How many periods the run covers - the length of the curve. */
  readonly horizonPeriods: number;
  /**
   * The parameter set that actually ran, read back from `params_json` - **or null if it could not
   * be**.
   *
   * Null is not "this run had no parameters": every run has them, and they are what makes it
   * replayable. It means the server could not deserialise the stored JSON and answered
   * without the field rather than failing the whole read - `SimulationService.fromJson` logs
   * *"Could not read params_json; returning the run without its parameters"* and returns null.
   *
   * So a view must render it as **unavailable**, on the same principle as every other absence in
   * this API: the seed, the replication count and the engine version are the run's
   * reproducibility record, and inventing a zero for any of them would be worse than saying so.
   * Dereferencing it unguarded is worse still - it took the whole results dashboard down with it.
   */
  readonly params: SimulationParams | null;
  /**
   * When this run was restored from a project archive, or **null if this installation computed it**.
   *
   * Non-null is the whole of the claim: *these numbers were produced somewhere else*, possibly under
   * a different engine version - `params.engineVersion` names which. A restored run is a real `DONE`
   * run in every other respect: it freezes its network and it belongs in the comparison view.
   *
   * It is also the flag the discard confirmation of FR-20 raises its second warning from. Deleting a
   * restored run destroys the only copy this installation holds, and the dialog says so on a line of
   * its own - see `core/run-discard.ts`.
   */
  readonly importedAt: IsoInstant | null;
  /**
   * The id this run held in the installation that computed it; null unless it was restored.
   *
   * Identity is re-created on import rather than preserved, so a thesis citing "run 42" stays
   * resolvable after the archive lands in an instance where 42 means something else.
   */
  readonly sourceRunId: Id | null;
  /** Empty on any accepted run; a submission naming an unresolvable event is refused. */
  readonly unresolvedEventIds: readonly Id[];
}

/**
 * One period of a run's performance curve (`RUN_TIMESERIES`).
 *
 * Replication averages, not one replication's numbers. **Both curves are here**, which is what
 * makes the resilience triangle drawable without recomputation: `LOSS_AREA` is the area
 * between them.
 *
 * There is no baseline `totalDemand` because it would always equal {@link RunTimeseriesPoint.totalDemand} -
 * the paired replications share their demand realisations by construction. Baseline fill
 * rate is `baselineServedDemand / totalDemand`.
 */
export interface RunTimeseriesPoint {
  /** 0-based index within the horizon. */
  readonly period: number;
  readonly totalDemand: number;
  /** Mean demand met, across the disrupted replications. */
  readonly servedDemand: number;
  /** Mean total cost of the period - fixed, variable, transport, holding and shortage. */
  readonly cost: number;
  /** Mean demand met in the same period of the undisrupted baseline set. */
  readonly baselineServedDemand: number;
  readonly baselineCost: number;
  /**
   * Mean on-hand stock across the **whole network** at the end of the period.
   *
   * The total the per-element {@link NodeTimeseries.onHand} series sums to, and the column
   * `AVG_INVENTORY` averages over the horizon.
   */
  readonly endingInventory: number;
  /**
   * Mean material in transit or in processing dwell at the end of the period - shipped and paid for
   * but not yet anywhere.
   *
   * **Zero is a measurement, not a gap.** A network whose lead times are all zero holds nothing in
   * flight, which is exactly what `samples/simulation-verification-3-node/` is designed to show.
   * `AVG_PIPELINE` is this column's horizon mean.
   */
  readonly inPipeline: number;
}

/**
 * One node's whole horizon, as parallel arrays indexed by period (FR-18).
 *
 * Mirrors the backend's `NodeTimeseriesDto`. **Parallel arrays rather than a list of period
 * objects**, and that shape is the contract rather than a compression: a playback view binds one
 * array to one visual channel and re-binds it when the clock moves, so index `t` is period `t` and
 * no key lookup happens per frame.
 *
 * **{@link inboundLead} carries nulls, and a null is a claim** - nothing was dispatched toward this
 * node in that period. A 0 would say material arrived instantly, which is a different and false
 * statement (absent renders absent, never zero).
 */
export interface NodeTimeseries {
  readonly nodeId: Id;
  /** The node's name as the run's (frozen) network held it - the label a view draws. */
  readonly name: string;
  /** End-of-period stock, mean across the disrupted replications. */
  readonly onHand: readonly number[];
  /** Inbound pipeline at period end: shipments on the wire plus this node's processing dwell. */
  readonly inTransit: readonly number[];
  /** What the pipeline delivered here this period - the arc's flow, one period later. */
  readonly arrivals: readonly number[];
  /** Demand met here; zero anywhere but a CUSTOMER. */
  readonly served: readonly number[];
  /** Demand wanted and not got; zero anywhere but a CUSTOMER. */
  readonly unserved: readonly number[];
  /** Flow across this node's own capacity arc - production at a supply origin, pass-through else. */
  readonly throughput: readonly number[];
  /** The period's availability multiplier in [0,1]: scenario events × random outages. */
  readonly availability: readonly number[];
  /** Dispatch-weighted inbound lead in periods; **null** where nothing was dispatched here. */
  readonly inboundLead: readonly (number | null)[];
  /** {@link onHand} in the undisrupted baseline set; a copy of it on a baseline run (FR-17). */
  readonly baselineOnHand: readonly number[];
  /** {@link served} in the undisrupted baseline set; likewise on a baseline run. */
  readonly baselineServed: readonly number[];
}

/**
 * One link's whole horizon, as parallel arrays indexed by period (FR-18).
 *
 * Mirrors the backend's `LinkTimeseriesDto`. **{@link flow} is what was dispatched, not what
 * arrived**: an arc with a lead time lands its flow at the target in a *later* period, so
 * a ribbon drawn from this array shows material leaving now and the target's
 * {@link NodeTimeseries.arrivals} shows it landing later. That one-period offset is the mechanic a
 * playback view is most likely to render wrongly.
 *
 * **{@link utilisation} carries nulls in two different situations, and neither is a zero**: an arc
 * with no declared capacity has no fraction to be at, and an arc an outage took to zero available
 * capacity is dark rather than idle. A link that carried nothing at full availability is a true 0.
 */
export interface LinkTimeseries {
  readonly linkId: Id;
  /** Tail node name, carried because an arc has no name of its own. */
  readonly sourceName: string;
  readonly targetName: string;
  /** Dispatched onto the arc this period, mean across the disrupted replications. */
  readonly flow: readonly number[];
  /** {@link flow} over the capacity actually available; **null** where there is no such fraction. */
  readonly utilisation: readonly (number | null)[];
  readonly availability: readonly number[];
  /** {@link flow} in the undisrupted baseline set; a copy of it on a baseline run (FR-17). */
  readonly baselineFlow: readonly number[];
}

/**
 * `GET /simulations/{runId}/timeseries/elements` - the per-element series of one run
 * (FR-18). Mirrors the backend's `ElementTimeseriesDto`.
 *
 * **{@link available} is the field to branch on, and it is not "is this list empty".** Three
 * different runs answer with empty lists and a client must not treat them alike: a run
 * still `QUEUED` or `RUNNING`, and a run that predates `V9__element_timeseries.sql` or was submitted
 * with `recordElementTimeseries: false`, both answer **false**; a single-element read naming an
 * element the run has no rows for answers **true** with an empty list.
 *
 * None of them is an error - this endpoint never 404s on the series and never 500s on an old run -
 * so a view branches on the flag and says *element detail unavailable* rather than animating a
 * network in which nothing moves.
 */
export interface ElementTimeseries {
  /** Whether this run recorded a per-element series at all. See the interface note. */
  readonly available: boolean;
  /** One entry per node, in node-id order. Empty when {@link available} is false. */
  readonly nodes: readonly NodeTimeseries[];
  /** One entry per link, in link-id order. Empty when {@link available} is false. */
  readonly links: readonly LinkTimeseries[];
}

/**
 * `GET /simulations/{runId}` - the whole of one run.
 *
 * Available before the run finishes: a `QUEUED` or `RUNNING` run answers with its record and two
 * empty lists rather than a 404, so the dashboard can open on a run the moment it is submitted.
 *
 * The two halves are also fetchable separately, as `/results` and `/timeseries` - a
 * comparison listing eight variants wants a dozen metric rows each and not eight horizons of
 * per-period points, and a curve redrawn on a zoom wants the opposite.
 */
export interface SimulationResults {
  readonly run: SimulationRun;
  /** The simulated suite with 95% intervals; empty until the run completes. */
  readonly metrics: readonly MetricResult[];
  /** The per-period curves, disrupted and baseline; empty until the run completes. */
  readonly timeseries: readonly RunTimeseriesPoint[];
}
