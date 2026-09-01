import { JobStatus, SimulationRun, isTerminalJobStatus } from './models';

/**
 * What a "discard runs" confirmation says, and what it must be sure of first (FR-20).
 *
 * Pure - no Angular, no HTTP - for the reason every other rule-bearing module in this folder is
 * pure: the part that has to be right is the wording of an irreversible question, and a component
 * spec cannot read wording without rendering a modal.
 *
 * ## The two questions this module answers
 *
 * **Can this be discarded at all?** `DELETE /networks/{id}/runs` is refused whole while any run is
 * `QUEUED` or `RUNNING` (`RUN_ACTIVE`, 409), and refusing whole is deliberate: a half-discard would
 * destroy every completed result and leave the network frozen anyway, since an active run locks it
 * too. So the editor does not open a confirmation it already knows will fail -
 * {@link activeRuns} finds the runs in the way and {@link activeRunsBlocker} writes the sentence
 * that names them and the remedy.
 *
 * **What exactly is being destroyed?** The dialog counts the runs about to go and warns
 * separately when one of them is a restored archive result. Both come from the run records of
 * `GET /networks/{id}/runs`; nothing here recomputes or guesses either.
 *
 * ## Why the typed phrase is what it is
 *
 * FR-20 confirms a discard "with the typed discipline of FR-15" - but not with FR-15's *phrase*.
 * That one is the owning project's name, chosen because a network shares its name with every variant
 * of it, so the network's own name would not identify which one is going. Here the answer
 * to that objection is available directly: a network's **name and version together** are unique
 * within a project, so `Baseline v2` names exactly which configuration's results are about to be
 * destroyed - and it is the string the editor toolbar and the fork prompt both already display.
 *
 * A single run is simpler still: `run 12` is unique outright, and the run id is on screen in the run
 * panel's report and in the results dashboard's header. In both cases the phrase names *what is
 * going*, which is the rule `shared/confirm-dialog` states and the product catalogue's own
 * confirmation follows.
 */

/** Everything `shared/confirm-dialog` needs for one discard, assembled in one place. */
export interface DiscardConfirm {
  readonly title: string;
  readonly message: string;
  readonly details: readonly string[];
  /** Typed before the action is enabled - see the module note on why it is this string. */
  readonly requiredPhrase: string;
  readonly phraseLabel: string;
  readonly confirmLabel: string;
}

/** The subset of a network the confirmation needs; keeps this module off the whole `Network` DTO. */
export interface DiscardNetwork {
  readonly name: string;
  readonly version: number;
}

/** How many runs are listed by name before the rest are summarised. */
const LISTED_RUNS = 6;

/**
 * Runs a job still owns, so nothing can be deleted until they settle (FR-20).
 *
 * Derived from {@link isTerminalJobStatus} rather than from its own list of statuses, because
 * "`QUEUED` or `RUNNING`" and "not terminal" are the same set and must stay the same set - the
 * backend's `SimulationStatus.isActive()` is defined the same way against the same five values.
 */
export function activeRuns(runs: readonly SimulationRun[]): readonly SimulationRun[] {
  return runs.filter((run) => !isTerminalJobStatus(run.status));
}

/** Runs restored from a project archive - the second warning. */
export function restoredRuns(runs: readonly SimulationRun[]): readonly SimulationRun[] {
  return runs.filter((run) => run.importedAt !== null);
}

/**
 * Runs that hold their network frozen.
 *
 * `FAILED` and `CANCELLED` produce no authoritative results and so lock nothing - which is why a
 * network whose only runs failed is editable, and why discarding those changes nothing about
 * editing. The dialog says which of the two cases it is rather than promising an unlock it cannot
 * deliver.
 */
export function lockingRuns(runs: readonly SimulationRun[]): readonly SimulationRun[] {
  return runs.filter(
    (run) =>
      run.status === JobStatus.QUEUED ||
      run.status === JobStatus.RUNNING ||
      run.status === JobStatus.DONE,
  );
}

/**
 * Why the discard cannot be offered yet, or null.
 *
 * Shown as the editor's action banner rather than inside a dialog, because there is nothing to
 * confirm: the server would refuse the request and delete nothing, and asking someone to type a
 * phrase for a guaranteed 409 is friction that buys nothing. The sentence names the runs and the
 * remedy, since cancelling is a gesture the run panel already has.
 */
export function activeRunsBlocker(runs: readonly SimulationRun[]): string | null {
  const active = activeRuns(runs);
  if (!active.length) {
    return null;
  }
  const listed = active.map((run) => `${describeRun(run)} is ${run.status}`).join(', ');
  return (
    `${active.length === 1 ? 'A run is' : `${active.length} runs are`} still executing ` +
    `(${listed}), so nothing can be discarded. The request is refused whole rather than deleting ` +
    'the finished runs and leaving the network frozen by the unfinished one. Cancel it from the ' +
    'run panel, then try again.'
  );
}

/**
 * The confirmation for discarding **every** run of one network (FR-20).
 *
 * Call only when {@link activeRunsBlocker} is null.
 */
export function discardNetworkRunsConfirm(
  network: DiscardNetwork,
  runs: readonly SimulationRun[],
): DiscardConfirm {
  const label = `${network.name} v${network.version}`;
  const count = runs.length;
  const details: string[] = [];

  if (count) {
    details.push(`${count === 1 ? 'The run' : `All ${count} runs`}: ${listRuns(runs)}.`);
    details.push(...commonDetails(runs));
    details.push(
      lockingRuns(runs).length > 0
        ? `${label} becomes editable again as soon as this completes - the freeze is derived from ` +
            'these rows, so there is nothing else to reset.'
        : 'None of these runs was freezing the network: a FAILED or CANCELLED run produces no ' +
            'authoritative results and holds nothing. Editing is unaffected either way.',
    );
    details.push(
      'Forking a variant is the other way out, and the opposite trade: it keeps every result and ' +
        'gives you a new network to edit. Discarding says the results were not worth keeping.',
    );
  }

  return {
    title: 'Discard this network’s runs?',
    message:
      count === 0
        ? `${label} has no simulation runs, so there is nothing to discard. It is already editable.`
        : `Delete ${count === 1 ? 'the simulation run' : `all ${count} simulation runs`} of ` +
          `${label}, and everything ${count === 1 ? 'it' : 'they'} produced. This cannot be undone ` +
          'and the results cannot be recovered from this application.',
    details,
    requiredPhrase: label,
    phraseLabel: 'network name and version',
    confirmLabel:
      count === 1 ? 'Discard the run and edit in place' : 'Discard the runs and edit in place',
  };
}

/** The confirmation for deleting **one** run - the run panel's report and the results dashboard. */
export function discardRunConfirm(run: SimulationRun): DiscardConfirm {
  const details = [...commonDetails([run])];
  details.push(
    lockingRuns([run]).length
      ? `If this is the last run holding ${run.networkName} frozen, that network becomes editable ` +
          'again as soon as this completes. If other runs remain, it stays frozen - the ' +
          'server decides, not this screen.'
      : `This run holds nothing frozen: a ${run.status} run produces no authoritative results, ` +
          'so nothing about editing its network changes.',
  );

  return {
    title: 'Delete this run?',
    message:
      `Delete ${describeRun(run)} of ${run.networkName}, and everything it produced. This cannot ` +
      'be undone and the results cannot be recovered from this application.',
    details,
    requiredPhrase: `run ${run.id}`,
    phraseLabel: 'run id',
    confirmLabel: 'Delete this run',
  };
}

/**
 * What a run *applied* - `Baseline`, or the scenario's name (FR-17, FR-21).
 *
 * Split out of {@link describeRun} when the run history of FR-21 landed: the history tile states
 * the scenario on a line of its own, and a second implementation of the
 * "no name on the wire" fallback would let a tile and this dialog disagree about what a run was -
 * about the very run one of them is offering to delete.
 *
 * `scenarioId === null` is the baseline test and the *only* one; `scenarioName` is null
 * for a baseline run and may also be absent for a scenario since deleted, which is what the
 * `Scenario #id` fallback is for. Both arrive as explicit nulls through `core/api-nulls.ts`.
 */
export function runScenarioLabel(run: SimulationRun): string {
  return run.scenarioId === null
    ? 'Baseline'
    : (run.scenarioName ?? `Scenario #${run.scenarioId}`);
}

/** `Baseline (run #12, DONE)` - and `, restored` when the run came out of an archive. */
export function describeRun(run: SimulationRun): string {
  const label = runScenarioLabel(run);
  const marks = [`run #${run.id}`, run.status];
  if (run.importedAt !== null) {
    marks.push('restored');
  }
  return `${label} (${marks.join(', ')})`;
}

/** The lines that read the same whether one run is going or twenty. */
function commonDetails(runs: readonly SimulationRun[]): string[] {
  const details: string[] = [
    'Each run’s metric suite and all three of its time series - the network curve and the ' +
      'per-node and per-link series playback replays - are deleted with it.',
  ];

  const restored = restoredRuns(runs);
  if (restored.length) {
    // A distinct warning, on a line of its own: these numbers were computed
    // somewhere else and restored, so this application may hold the only copy of them.
    const dates = [...new Set(restored.map((run) => (run.importedAt ?? '').slice(0, 10)))]
      .filter((date) => date.length > 0)
      .join(', ');
    const opening =
      restored.length === 1
        ? 'One of these is a restored archive result'
        : `${restored.length} of these are restored archive results`;
    details.push(
      `⚠ ${opening}${dates ? ` (imported ${dates})` : ''}: computed by another installation and ` +
        'brought in from a project archive. Deleting them destroys the only copy this application ' +
        'holds - the archive file itself is unaffected, and restoring it again re-creates them.',
    );
  }

  details.push(
    'The network’s structural metrics are not touched: they belong to the network rather than to ' +
      'any run, and nothing about the structure is changing.',
  );
  return details;
}

/** Run labels for the dialog, capped so a network with forty runs does not fill the screen. */
function listRuns(runs: readonly SimulationRun[]): string {
  const shown = runs.slice(0, LISTED_RUNS).map(describeRun);
  const hidden = runs.length - shown.length;
  return hidden > 0 ? `${shown.join('; ')}, and ${hidden} more` : shown.join('; ');
}
