import { Injectable, OnDestroy, computed, effect, inject, signal, untracked } from '@angular/core';
import { Subscription, firstValueFrom } from 'rxjs';

import { WireResults, normaliseResults } from '../../core/api-nulls';
import { ApiService } from '../../core/api.service';
import { JobPollingService } from '../../core/job-polling.service';
import {
  Id,
  Job,
  JobStatus,
  ProvisionalFigures,
  SimulationAccepted,
  SimulationRequest,
  SimulationResults,
  SimulationRun,
  isTerminalJobStatus,
} from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import { runScenarioLabel } from '../../core/run-discard';
import { ScenariosStore } from '../scenario-builder/scenarios.store';
import {
  CurvePoint,
  LossRegion,
  lossRegions as computeLossRegions,
  shadedArea as computeShadedArea,
  toCurve,
} from '../simulations/curve-geometry';
import { NetworkEditorStore } from './network-editor.store';
import { settlesHistory } from './run-history';

/** The run this session submitted, as it was labelled at submission - the progress block's title. */
interface LaunchedRun {
  readonly runId: Id;
  /** "Plant outage", or "Baseline" for a run that applied no scenario (FR-17). */
  readonly label: string;
  readonly scenarioId: Id | null;
}

/**
 * Running a simulation from the editor (FR-17).
 *
 * > "A configuration is judged where it is built."
 *
 * Two runs are on offer against the network on screen: the **baseline** - no scenario, the first
 * thing worth running on a network just imported or drawn, and the comparator every disrupted run
 * is read against - and any **scenario** in the project, including one just authored in the
 * disruptions panel next door.
 *
 * ## Everything asynchronous goes through `JobPollingService`
 *
 * The store submits, hands the `jobId` to {@link JobPollingService.poll}, and renders what comes
 * back - no timer of its own, the same discipline as `RunLauncherStore`. What is new here
 * is what the poll now carries: the **provisional figures** of FR-17, streaming statistics over the
 * completed replications, surfaced through {@link partial} and marked provisional wherever they are
 * shown. They are a partial sample that the persisted suite replaces on completion; presenting them
 * as results would invite a conclusion drawn from twelve replications of a hundred, and persisting
 * or exporting them is forbidden outright.
 *
 * ## Immutability is reached sooner, not routed around
 *
 * The network freezes the moment a run is accepted - at the 202, before the first
 * replication. This store then calls {@link NetworkEditorStore.refreshNetworkRecord}, so the frozen
 * banner appears at once rather than on the next refused edit; a cancelled or failed run releases
 * the network and the same refresh shows that too. The panel says all of this **before** the button
 * is pressed, as the standalone launcher does: running from the editor is not a way around
 * immutability, it is a way of reaching it sooner - which is the point.
 *
 * ## The report is read in place, and compared through runs
 *
 * On completion the run's results are fetched once and rendered beside the canvas - the same
 * `GET /simulations/{runId}` and the same curve geometry the dashboard uses, so the two surfaces
 * cannot draw different triangles from one run.
 *
 * A completed run also **starts playing on the canvas** (FR-18): the
 * report landing bumps {@link autoPlayToken}, which `PlaybackStore` reads as "rewind and play". See
 * that field for why the trigger is a token rather than a call.
 *
 * ## A run of an hour ago is watched, not re-run (FR-21)
 *
 * {@link selectRun} loads any `DONE` run of this network's history through **the same path** a
 * just-completed run takes - {@link loadReport}, one `GET /simulations/{runId}`, one write to
 * {@link report}, one bump of {@link autoPlayToken}. That is deliberate to the point of being the
 * design: two paths into the report state would be two chances for the panel and the canvas to
 * disagree about which run is on screen, and the second path would be the one nobody watches while
 * developing, because it is the one that does not run when you press the button.
 *
 * The history list itself is **not** held here. It is `NetworkEditorStore.runs()` - the list the
 * FR-20 discard confirmation is already built from (*"one list, two readers"*) - and this
 * store's part is knowing *when* it has gone stale: when the panel opens, when a job settles, and
 * after a deletion. No timer, and no second poll: an active run submitted elsewhere cannot be
 * followed live at all, because its job id was issued to its submitter alone.
 *
 * Session-scoped, like every store on the editor route: a report belongs to the editing session
 * reading it. The runs themselves are durable - which is what FR-21 is for.
 */
@Injectable()
export class EditorRunStore implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly jobs = inject(JobPollingService);
  private readonly editor = inject(NetworkEditorStore);
  /** The project's scenario list - the same root store the scenario builder and panel read. */
  readonly scenarios = inject(ScenariosStore);

  private readonly _submitting = signal(false);
  private readonly _accepted = signal<SimulationAccepted | null>(null);
  /** The scenario the accepted run applied, or null for a baseline run - the progress label. */
  private readonly _acceptedScenario = signal<LaunchedRun | null>(null);
  private readonly _job = signal<Job | null>(null);
  private readonly _cancelling = signal(false);
  private readonly _error = signal<string | null>(null);
  private readonly _report = signal<SimulationResults | null>(null);
  private readonly _active = signal(false);
  private readonly _autoPlay = signal(0);
  private readonly _deleting = signal<Id | null>(null);
  private readonly _selectedRun = signal<SimulationRun | null>(null);
  private readonly _loadingReport = signal(false);

  readonly submitting = this._submitting.asReadonly();
  readonly accepted = this._accepted.asReadonly();
  readonly job = this._job.asReadonly();
  readonly cancelling = this._cancelling.asReadonly();
  readonly error = this._error.asReadonly();
  /** The reported run's results - a run this session finished, or one picked out of the history. */
  readonly report = this._report.asReadonly();
  /**
   * The history tile that is selected, whatever its status (FR-21).
   *
   * Not the same thing as {@link reportRun}: a `QUEUED`, `RUNNING`, `FAILED` or `CANCELLED` tile
   * selects to its status and has no report to be the subject of, and the panel still has to show
   * *which* tile the status belongs to.
   */
  readonly selectedRun = this._selectedRun.asReadonly();
  /** True while a selected run's report is being fetched - the tile's own spinner (FR-21). */
  readonly loadingReport = this._loadingReport.asReadonly();
  /** The run whose deletion is in flight, or null (FR-20). */
  readonly deletingRunId = this._deleting.asReadonly();
  /** True while any deletion is in flight - the dialog's busy flag. */
  readonly deleting = computed(() => this._deleting() !== null);

  /**
   * Bumped once when a report lands - a run this session launched, or a `DONE` tile picked out of
   * the history (FR-18; "Run history", FR-21).
   *
   * The request was "click Run, see it simulate": a completed run is the moment playback has
   * something to show, and requiring a second gesture to start it would make the animation a feature
   * to be found rather than the answer to the button just pressed. `PlaybackStore` watches this
   * token and rewinds-and-plays exactly once per bump. FR-21 asks for the same behaviour from the
   * other direction - *"selecting a `DONE` tile does what finishing a run does"* - so selecting one
   * bumps this by the same statement in {@link loadReport} rather than by a second mechanism.
   *
   * **Re-selecting the tile already loaded bumps nothing**, because {@link selectRun} returns before
   * the fetch: a token is idempotent per bump but not per gesture, and a click on the tile whose run
   * is already playing must not throw the clock back to period 0 halfway through a watch.
   *
   * **A counter rather than a call into playback, because the dependency runs the other way.**
   * `PlaybackStore` injects this store to read the report; this store injecting it back would be a
   * cycle. A monotonic token is also what makes the trigger *idempotent* - a store that re-emits the
   * same report (a re-render, a resubscribed effect) does not restart the animation, and the
   * distinction between "the same run again" and "a new run" stays this store's to make.
   *
   * Deliberately **not** bumped by the provisional phase: partial figures stay as they are while the
   * job runs (FR-17), and there is no series to play until the run is DONE.
   */
  readonly autoPlayToken = this._autoPlay.asReadonly();

  /** True from submission until the job settles - the progress panel's lifetime. */
  readonly running = computed(() => {
    const job = this._job();
    return this._accepted() !== null && (job === null || !isTerminalJobStatus(job.status));
  });

  readonly progressPercent = computed(() => {
    const job = this._job();
    return job ? Math.max(0, Math.min(100, Math.round(job.progress * 100))) : 0;
  });

  /**
   * The provisional figures of FR-17, while the job runs.
   *
   * Straight off the poll, and gone the moment the job settles - the server discards them on any
   * terminal state, because from then the persisted suite is authoritative. Everything rendering
   * these must say "provisional" on screen.
   */
  readonly partial = computed<ProvisionalFigures | null>(() => this._job()?.partial ?? null);

  readonly jobStatus = computed(() => this._job()?.status ?? null);
  readonly jobFailed = computed(() => this.jobStatus() === JobStatus.FAILED);
  readonly jobCancelled = computed(() => this.jobStatus() === JobStatus.CANCELLED);

  /** What the run in flight is evaluating - "Baseline" or the scenario's name. */
  readonly runLabel = computed(() => this._acceptedScenario()?.label ?? null);

  // ---------------------------------------------------------------------- the report

  readonly reportRun = computed(() => this._report()?.run ?? null);

  /**
   * What the **reported** run applied, read off the run record rather than off the submission.
   *
   * FR-21 made the two different questions: a report can now belong to a run this session never
   * launched, for which `_acceptedScenario` is null and always will be. The record carries
   * `scenarioName` from the server, which is also the better answer for a session run - a scenario
   * renamed since submission reads as it is now rather than as this tab last saw it.
   */
  readonly reportLabel = computed(() => {
    const run = this.reportRun();
    return run === null ? null : runScenarioLabel(run);
  });

  /** Both series as fill rates - the same geometry the dashboard draws. */
  readonly curve = computed<readonly CurvePoint[]>(() =>
    toCurve(this._report()?.timeseries ?? []),
  );

  readonly lossRegions = computed<readonly LossRegion[]>(() => computeLossRegions(this.curve()));

  readonly shadedArea = computed(() => computeShadedArea(this.lossRegions()));

  readonly hasCurve = computed(() => this.curve().length > 0);

  /** The simulated suite of the completed run, in the registry's own order. */
  readonly reportMetrics = computed(() => this._report()?.metrics ?? []);

  /** True when the completed run was the baseline run of FR-17. */
  readonly reportIsBaseline = computed(() => this.reportRun()?.scenarioId === null);

  private poll: Subscription | null = null;
  private loadedListForProject: Id | null = null;
  /**
   * Which report read is current, so a slow answer for a tile the researcher has already left
   * cannot land on top of the one they are watching - `PlaybackStore.elementRequest`'s rule, one
   * level up, and reachable here for the first time now that a report can be *chosen* (FR-21).
   */
  private reportRequest = 0;

  constructor() {
    // The scenario list is fetched on first activation, not when the editor loads - the same
    // deferral the metrics and disruptions panels practise. It is the shared root list, so a
    // scenario created in the disruptions panel is already in this picker.
    effect(
      () => {
        const projectId = this.editor.network()?.projectId ?? null;
        const active = this._active();
        untracked(() => {
          if (!active || projectId === null || this.loadedListForProject === projectId) {
            return;
          }
          this.loadedListForProject = projectId;
          if (this.scenarios.projectId() !== projectId) {
            this.scenarios.load(projectId);
          }
        });
      },
      { allowSignalWrites: true },
    );
  }

  /**
   * Whether the panel is open.
   *
   * Opening it re-reads the run history, so the panel opens with the network's whole
   * run list. On the *transition* only, so a re-render costs nothing, and read through
   * `untracked` because the caller is an effect over the panel's open flag - a tracked read of the
   * store's own signal would make that effect depend on what it just wrote.
   */
  setActive(active: boolean): void {
    const opening = active && !untracked(() => this._active());
    this._active.set(active);
    if (opening) {
      this.refreshHistory();
    }
  }

  /**
   * Re-read `GET /networks/{id}/runs` (FR-21).
   *
   * Three callers and no fourth: the panel opening, a job settling, and a deletion. That is the
   * refresh rule for the run history exactly - *"the list refreshes when a run
   * settles, and after every discard"* - and it is a list of gestures rather than an interval,
   * because there is nothing here for a timer to catch that one of these three does not.
   *
   * The whole-network discard needs no entry: `NetworkEditorStore.discardRuns` empties the list
   * itself on the 204, which is the same answer a re-read would give and one request cheaper.
   */
  refreshHistory(): void {
    void this.editor.loadNetworkRuns();
  }

  dismissError(): void {
    this._error.set(null);
  }

  // ------------------------------------------------------------------------ submission

  /**
   * Submits a run against the network on screen (FR-17).
   *
   * Pending canvas edits are **flushed first**: the run evaluates the network as the server holds
   * it, and the edit most likely to be sitting in the two-second window is the one the researcher
   * is running to evaluate. If the flush fails, the submission is abandoned rather than run against
   * a stale structure - the toolbar is already showing the save error and its remedy.
   *
   * @param scenarioId the scenario to apply, or null for the baseline run of FR-17
   */
  async submit(scenarioId: Id | null): Promise<void> {
    const network = this.editor.network();
    if (!network || this._submitting() || this.running()) {
      return;
    }
    this.stopPolling();
    this._submitting.set(true);
    this._error.set(null);
    this._job.set(null);
    this._accepted.set(null);
    this._report.set(null);
    // A new run is a new subject: the history selection goes with the report it belonged to, or the
    // panel would show last Tuesday's tile highlighted above this minute's progress bar (FR-21).
    this._selectedRun.set(null);

    try {
      await this.editor.flush();
      // Omitted, never null: the baseline run travels as an absent field (FR-17).
      const request: SimulationRequest =
        scenarioId === null
          ? { networkId: network.id }
          : { networkId: network.id, scenarioId };
      const accepted = await firstValueFrom(
        this.api.post<SimulationAccepted>('/simulations', request),
      );
      this._accepted.set(accepted);
      this._acceptedScenario.set({
        runId: accepted.runId,
        scenarioId,
        label: scenarioId === null ? 'Baseline' : this.scenarioName(scenarioId),
      });
      // The 202 froze the network. Show it now, not on the next refused edit.
      void this.editor.refreshNetworkRecord();
      this.follow(accepted.jobId);
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not submit the run.'));
    } finally {
      this._submitting.set(false);
    }
  }

  /** Cooperative cancellation. The run releases its network once CANCELLED lands. */
  async cancel(): Promise<void> {
    const accepted = this._accepted();
    if (!accepted || this._cancelling()) {
      return;
    }
    this._cancelling.set(true);
    try {
      await firstValueFrom(this.jobs.cancel(accepted.jobId));
    } catch (failure: unknown) {
      this._error.set(problemMessage(failure, 'Could not cancel the run.'));
    } finally {
      this._cancelling.set(false);
    }
  }

  // ------------------------------------------------------------------- the run history (FR-21)

  /**
   * Select a run from the history (FR-21).
   *
   * **A `DONE` tile does what finishing a run does.** Its report loads in place through
   * {@link loadReport} - the same method, the same endpoint and the same `autoPlayToken` bump the
   * settle path uses - so the panel and the canvas cannot end up describing two different runs, and
   * playback arms at the configured speed exactly as it does for a run that finished a moment ago.
   * That is the point of the requirement: a re-run costs minutes and, on a stochastic scenario,
   * answers a subtly different question.
   *
   * **Every other status selects to its status and nothing else.** There is no report to load -
   * the suite and all three series are written in one transaction with `DONE` - and an
   * active one cannot be followed live either, because its job id was issued to its submitter
   * alone. So the panel shows the status and offers a manual re-read; it does not start a second
   * poll against a job it was never given. Any report already on screen is dropped, because leaving
   * it under a newly selected tile would attribute one run's numbers to another.
   *
   * **Re-selecting the loaded run is a no-op**, deliberately: see {@link autoPlayToken}.
   */
  selectRun(run: SimulationRun): void {
    if (this._deleting() !== null) {
      return;
    }
    if (run.status !== JobStatus.DONE) {
      this._selectedRun.set(run);
      this._report.set(null);
      return;
    }
    // Already on screen, or already on the wire. Switching *between* tiles mid-read is allowed and
    // is what `reportRequest` is for; asking twice for the one already being fetched is not.
    if (this._report()?.run.id === run.id) {
      return;
    }
    if (this._loadingReport() && this._selectedRun()?.id === run.id) {
      return;
    }
    this._selectedRun.set(run);
    // A historical run is not a submission: the two run buttons come back rather than a "New run"
    // header, and no job is being followed. Clearing these is what makes the panel read as
    // *showing a run* rather than as having just launched one.
    this.stopPolling();
    this._accepted.set(null);
    this._acceptedScenario.set(null);
    this._job.set(null);
    void this.loadReport(run.id);
  }

  /**
   * `DELETE /simulations/{runId}` - discard one run (FR-20), from its history tile or from
   * the report on screen.
   *
   * The single-run form of the discard. It exists as well as the editor's whole-network "Unlock…"
   * because the two answer different questions: this one is *this result was not worth keeping*,
   * and it is the common case during iterative model building - several runs against one network,
   * one of which was a mistake.
   *
   * Three things follow a success. The history is re-read, since the list on screen has just been
   * contradicted (FR-21). **The report is dropped only if it was this run's** - deleting a tile
   * that is not the one being watched must not stop the playback of one that still exists - and
   * dropping it stops playback with it, since `PlaybackStore` follows {@link report}. And the
   * network row is re-read, exactly as it is at the 202 and when a job settles: deleting the last
   * locking run releases the freeze, and whether it was the last is the server's answer
   * rather than this store's.
   *
   * The caller confirms first. Nothing here is undoable, and the wording lives in `run-discard.ts`.
   *
   * @returns true when the run is gone
   */
  async deleteRun(run: SimulationRun): Promise<boolean> {
    if (this._deleting() !== null) {
      return false;
    }
    this._deleting.set(run.id);
    try {
      await firstValueFrom(this.api.delete<void>(`/simulations/${run.id}`));
      if (this._report()?.run.id === run.id) {
        this.reset();
      }
      if (this._selectedRun()?.id === run.id) {
        this._selectedRun.set(null);
      }
      this.refreshHistory();
      void this.editor.refreshNetworkRecord();
      return true;
    } catch (failure: unknown) {
      // `RUN_ACTIVE` (409) is reachable from a tile - a run submitted from another tab can start
      // between the list being read and the phrase being typed - so the server's own sentence is
      // shown verbatim rather than reworded, and the list is re-read so the tile stops lying.
      this._error.set(problemMessage(failure, 'Could not delete this run.'));
      this.refreshHistory();
      return false;
    } finally {
      this._deleting.set(null);
    }
  }

  /** Clears the last submission so the panel offers the two buttons again. */
  reset(): void {
    this.stopPolling();
    this._accepted.set(null);
    this._acceptedScenario.set(null);
    this._job.set(null);
    this._report.set(null);
    this._selectedRun.set(null);
    this._error.set(null);
    this._cancelling.set(false);
  }

  // ------------------------------------------------------------------------- internals

  private follow(jobId: string): void {
    this.poll = this.jobs.poll(jobId).subscribe({
      next: (job) => {
        const previous = this._job()?.status ?? null;
        this._job.set(job);
        // The list refreshes when a run settles. The rule is
        // `run-history.settlesHistory` - the transition **into** a terminal state and nothing else
        // - so every `QUEUED → RUNNING` tick and every re-emitted `DONE` costs no request, and all
        // three terminal states qualify because a `FAILED` run is a tile too. Specced there rather
        // than written inline, because "when does this list go stale" is the part that has to be
        // right if the panel is not to grow a second poll loop (FR-21).
        if (settlesHistory(previous, job.status)) {
          this.refreshHistory();
        }
        if (isTerminalJobStatus(job.status)) {
          void this.onSettled(job);
        }
      },
      error: (failure: unknown) => {
        // Evicted from the retention window. The run is durable; say so and stop.
        this._error.set(
          problemMessage(
            failure,
            'Lost track of the job. The run itself is unaffected - open its dashboard to see '
              + 'where it got to.',
          ),
        );
      },
    });
  }

  private async onSettled(job: Job): Promise<void> {
    // CANCELLED and FAILED release the network; DONE keeps it frozen. Either way the server owns
    // the answer, so ask it - the same refresh the submission made.
    void this.editor.refreshNetworkRecord();
    // The history refresh is not here: it belongs to the *transition*, which `follow` above sees
    // and this method does not - see `settlesHistory`.
    if (job.status !== JobStatus.DONE) {
      return;
    }
    const accepted = this._accepted();
    if (!accepted) {
      return;
    }
    await this.loadReport(accepted.runId, 'The run completed but its results could not be read.');
  }

  /**
   * `GET /simulations/{runId}` into {@link report}, then arm playback - **the one report path**.
   *
   * Both entrances land here: a run this session watched finish, and a `DONE` tile picked out of
   * the history (FR-21). One method rather than two because the two must not be able to differ -
   * the panel renders `report()` and `PlaybackStore` reads it for the clock, the horizon and the
   * element series, so a second loader that forgot the normalisation, or the token, or the run's own
   * period length would produce a panel and a canvas describing different things.
   *
   * @param runId the run to read
   * @param failureMessage what to say if the read fails, when the caller has better words for it
   */
  private async loadReport(runId: Id, failureMessage?: string): Promise<void> {
    const request = ++this.reportRequest;
    this._loadingReport.set(true);
    try {
      const wire = await firstValueFrom(this.api.get<WireResults>(`/simulations/${runId}`));
      if (request !== this.reportRequest) {
        // A later selection has already claimed the panel; a slow answer for the tile the
        // researcher has left must not land on top of the one they are watching.
        return;
      }
      // Absence becomes null here, as it does on the dashboard's own read of the same endpoint
      // (`core/api-nulls.ts`). `reportIsBaseline` is `scenarioId === null`, and the API omits a
      // baseline run's null scenario entirely - so without this the panel labels the run as a
      // disruption run and looks for a triangle that cannot exist (FR-17).
      this._report.set(normaliseResults(wire));
      // The report is in place, so the clock has a horizon and a period length to read: ask for
      // playback (FR-18). Last, and only on the success path - a run whose results could
      // not be read has nothing to play, and the error below is what the researcher needs to see.
      this._autoPlay.update((token) => token + 1);
    } catch (failure: unknown) {
      if (request !== this.reportRequest) {
        return;
      }
      this._error.set(problemMessage(failure, failureMessage ?? 'Could not read that run.'));
    } finally {
      if (request === this.reportRequest) {
        this._loadingReport.set(false);
      }
    }
  }

  private scenarioName(scenarioId: Id): string {
    return (
      this.scenarios.scenarios().find((scenario) => scenario.id === scenarioId)?.name ??
      `Scenario #${scenarioId}`
    );
  }

  private stopPolling(): void {
    this.poll?.unsubscribe();
    this.poll = null;
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }
}
