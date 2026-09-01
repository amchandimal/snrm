import {
  Id,
  IsoInstant,
  JobStatus,
  SimulationRun,
  SimulationStatus,
  isTerminalJobStatus,
} from '../../core/models';
import { activeRuns, runScenarioLabel } from '../../core/run-discard';

/**
 * The editor's run history, as tiles (FR-21).
 *
 * > "The panel's memory used to be the session's: a run submitted an hour ago from another tab
 * > existed in the database and nowhere on screen."
 *
 * Pure - no Angular, no HTTP - for the reason every other rule-bearing module in this feature is
 * pure: what has to be right is *what a tile claims about a run*, and each claim is a reading of a
 * nullable DTO field that a component spec could only reach by rendering a panel.
 *
 * ## What a tile says, and where each part comes from
 *
 * A tile carries four readings and this module derives exactly those four, from the run record of
 * `GET /networks/{id}/runs` and from nothing else:
 *
 * - **when it ran** - {@link RunTile.when}: the finished timestamp, or the started one, or *queued*.
 *   Three states rather than a nullable date, because "not finished yet" and "not started yet" are
 *   different facts about a run and a blank cell states neither.
 * - **which scenario it applied** - {@link RunTile.scenario}, through `runScenarioLabel` in
 *   `core/run-discard.ts`, so the tile and the deletion dialog name a run the same way. FR-21 asks
 *   for *"Baseline - no scenario"* in full here, where there is room to say it; the dialog's terser
 *   `Baseline` is the same fact in a sentence that already has a subject.
 * - **its status, wherever that is not `DONE`** - {@link RunTile.badge}. A badge on every tile would
 *   make the ordinary case shout as loudly as the exceptional one, which is the reading FR-21 asks
 *   for by naming the exception.
 * - **restored provenance** - {@link RunTile.restored}, from `importedAt`. Absent on every
 *   run this installation computed, and the wire omits it entirely - hence the normalisation in
 *   `core/api-nulls.ts`, without which every tile would claim to be an archive result.
 *
 * ## Two things a tile decides, and both are the server's rules restated
 *
 * {@link RunTile.loadable} is `status === DONE`: no other status has a report to load, because the
 * metric suite and the three series are written in one transaction with the status. And
 * {@link RunTile.deletable} is `activeRuns([run]).length === 0` - the editor does not open a
 * confirmation it knows the server will refuse (`RUN_ACTIVE`), which is the rule
 * `openDiscardPrompt` already follows for the whole-network form. Both delegate rather than
 * re-testing a status set: a fifth status added to the vocabulary must not be silently loadable.
 *
 * ## The order is the server's
 *
 * `GET /networks/{id}/runs` answers newest first (`findByNetworkIdOrderByIdDesc`), and this module
 * preserves that order rather than re-sorting. A client sort on `finishedAt` would have to place a
 * `QUEUED` run - which has no timestamp at all - somewhere arbitrary, and the run ids are already
 * monotonic in submission order, which is the "newest" FR-21 means.
 */

/** When a run ran, in the three states a run record can be in. */
export interface RunTileWhen {
  /** `finished` and `started` carry {@link at}; `queued` never does. */
  readonly kind: 'finished' | 'started' | 'queued';
  /** The instant to render, or null when the run has not started. */
  readonly at: IsoInstant | null;
}

/** One run of the network, as the run panel's history renders it (FR-21). */
export interface RunTile {
  readonly runId: Id;
  readonly status: SimulationStatus;
  /** When it finished, when it started, or that it is still queued. */
  readonly when: RunTileWhen;
  /** `Baseline - no scenario`, or the scenario's name. */
  readonly scenario: string;
  /** The status to badge, or null where the status is `DONE` and needs no saying. */
  readonly badge: SimulationStatus | null;
  /** When this run was restored from a project archive, or null. */
  readonly restored: IsoInstant | null;
  /** True only for a `DONE` run: every other status has no report to load (FR-21). */
  readonly loadable: boolean;
  /** False while a job still owns the run - the server would refuse the delete (FR-20). */
  readonly deletable: boolean;
}

/** The phrase FR-21 asks a baseline tile to read, in full. */
const BASELINE_TILE_LABEL = 'Baseline - no scenario';

/** One run's tile. See the module note for where each field comes from. */
export function toRunTile(run: SimulationRun): RunTile {
  return {
    runId: run.id,
    status: run.status,
    when: whenOf(run),
    scenario: run.scenarioId === null ? BASELINE_TILE_LABEL : runScenarioLabel(run),
    badge: run.status === JobStatus.DONE ? null : run.status,
    restored: run.importedAt,
    loadable: run.status === JobStatus.DONE,
    deletable: activeRuns([run]).length === 0,
  };
}

/** The whole history, in the order the server answered - newest first. See the module note. */
export function toRunTiles(runs: readonly SimulationRun[]): readonly RunTile[] {
  return runs.map(toRunTile);
}

/**
 * One candidate for the side-by-side of FR-17, keyed by run.
 *
 * The `?runIds=` form of the comparison is the only selector that can seat two runs of *one*
 * network as two columns, which is exactly what baseline-versus-disruption is.
 */
export interface ComparisonCandidate {
  readonly runId: Id;
  /** The other run's scenario label, for the link's own text. */
  readonly scenario: string;
  /** The `runIds` query value: the candidate first, the loaded run second. */
  readonly runIds: string;
}

/**
 * Which runs the loaded report can be compared against (FR-21).
 *
 * **Drawn from the history rather than from what this session happened to run**, which is the whole
 * of FR-21's last clause: *"the history is where baseline-versus-disruption pairs come from once the
 * session that ran them is gone"*. A researcher who ran a baseline on Monday and a disruption on
 * Tuesday has two comparable runs and no session that saw both.
 *
 * Only `DONE` tiles qualify, and the loaded run is never offered against itself. The first is the
 * server's rule rather than tidiness: `?runIds=` refuses a non-`DONE` run with `RUN_NOT_DONE` (409)
 * rather than seating it as an empty column, so offering one would be offering a link that 409s.
 *
 * @param tiles the history, newest first
 * @param loadedRunId the run whose report is on screen, or null when none is
 */
export function comparisonCandidates(
  tiles: readonly RunTile[],
  loadedRunId: Id | null,
): readonly ComparisonCandidate[] {
  if (loadedRunId === null) {
    return [];
  }
  return tiles
    .filter((tile) => tile.loadable && tile.runId !== loadedRunId)
    .map((tile) => ({
      runId: tile.runId,
      scenario: tile.scenario,
      runIds: `${tile.runId},${loadedRunId}`,
    }));
}

/**
 * Whether a job status change means the list on screen has stopped describing the network
 * (*"The list refreshes when a run settles"*).
 *
 * True on the **transition into** a terminal state and on nothing else. Three consequences, and all
 * three are about not adding a polling loop where none is called for (FR-21):
 *
 * - `QUEUED → RUNNING` is not a settle. The row exists either way and its timestamp is not what the
 *   history is read for; re-reading the list on every poll tick would be a second poll loop wearing
 *   the first one's clothes.
 * - `DONE → DONE` is not a settle. The poll re-emits the same terminal status while the results are
 *   fetched, and a refresh per emission would issue the same request several times over.
 * - `null → DONE` **is** one: a panel that opens onto an already-settled job has a list that predates
 *   it.
 *
 * `FAILED` and `CANCELLED` settle exactly as `DONE` does. They produce no report, but they are rows
 * in the list and they release the freeze, so a history that still showed them as
 * `RUNNING` would be describing a network that no longer exists.
 */
export function settlesHistory(
  previous: SimulationStatus | null,
  next: SimulationStatus | null,
): boolean {
  // `isTerminalJobStatus` rather than a list of three statuses here, for the reason
  // `run-discard.activeRuns` gives for the complementary set: "settled" and "not active" are one
  // partition of the five values and must stay one, on both ends of the wire.
  return next !== null && isTerminalJobStatus(next) && previous !== next;
}

/**
 * The tile's timestamp, in order of preference: finished, else started, else queued.
 *
 * A run that finished carries both timestamps and the finished one is what FR-21 asks for - *"the
 * date and time it finished"* - because that is when the numbers came into existence. A run still
 * executing has only a start, and one still queued has neither; neither is rendered as a blank,
 * since a blank cell where a date belongs reads as missing data rather than as an unstarted run.
 */
function whenOf(run: SimulationRun): RunTileWhen {
  if (run.finishedAt !== null) {
    return { kind: 'finished', at: run.finishedAt };
  }
  if (run.startedAt !== null) {
    return { kind: 'started', at: run.startedAt };
  }
  return { kind: 'queued', at: null };
}
