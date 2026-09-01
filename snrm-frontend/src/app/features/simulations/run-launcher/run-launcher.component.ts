import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';

import { formatUnits } from '../../../core/metric-display';
import { Id, JobStatus, SimulationParamsRequest } from '../../../core/models';
import { RunLauncherStore } from '../run-launcher.store';

/**
 * The run launcher (FR-06).
 *
 * > "simulations - run launcher, job monitor, results dashboard"
 *
 * Three parts in one page, because they are one act: choose what to evaluate, watch it run, open the
 * answer. Splitting the monitor onto its own route would put a navigation between pressing a button
 * and seeing whether it worked.
 *
 * ## What the form says out loud
 *
 * **The network freezes.** Submitting makes the chosen network immutable, and the warning
 * is beside the picker rather than in a toast afterwards, because by then the researcher has already
 * chosen.
 *
 * **The job runs twice the replications you asked for.** Every simulation includes the paired
 * undisrupted baseline set that `TTR`, `LOSS_AREA`, `RESILIENCE_INDEX` and `DISRUPTION_COST_DELTA`
 * are all defined against. A researcher who asks for 100 and watches 200 go by is entitled
 * to know why before it happens.
 *
 * **Defaults come from the scenario.** Replications and seed live on the scenario; the
 * fields here are overrides for one run, and leaving them empty is not "zero" but "use the
 * scenario's". The placeholders show what will actually be used.
 *
 * **Zero noise is the default and is a choice.** `demandNoiseCv` and `timingJitterPeriods` default to
 * 0, so a run is exactly deterministic unless asked otherwise - which is what makes a result
 * checkable by hand. The form does not hide that behind a "basic/advanced" toggle.
 */
@Component({
  selector: 'app-run-launcher',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './run-launcher.component.html',
  styleUrl: './run-launcher.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunLauncherComponent {
  readonly store = inject(RunLauncherStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly projectId = toSignal(
    this.route.paramMap.pipe(map((params) => Number(params.get('projectId')) || null)),
    { initialValue: null },
  );

  /**
   * The scenario picker's sentinel for the baseline run of FR-17 - no scenario at all.
   *
   * A sentinel rather than reusing null, because null is already "nothing chosen yet" and the two
   * must not collapse: an untouched form must not quietly submit a baseline run.
   */
  readonly BASELINE = 'baseline' as const;

  // ---- the form
  readonly networkId = signal<Id | null>(null);
  /** The picker's raw choice: nothing yet, the baseline sentinel, or a scenario id. */
  readonly scenarioChoice = signal<Id | 'baseline' | null>(null);
  readonly replications = signal<number | null>(null);
  readonly seed = signal<number | null>(null);
  readonly demandNoiseCv = signal<number | null>(null);
  readonly timingJitterPeriods = signal<number | null>(null);
  readonly includeRandomFailures = signal(true);
  readonly baselineSuppressesFailures = signal(false);
  readonly showAdvanced = signal(false);

  /** The chosen scenario id, or null both before a choice and for the baseline run. */
  readonly scenarioId = computed<Id | null>(() => {
    const choice = this.scenarioChoice();
    return choice === null || choice === this.BASELINE ? null : choice;
  });

  /** True when the baseline run of FR-17 was explicitly chosen. */
  readonly baselineChosen = computed(() => this.scenarioChoice() === this.BASELINE);

  readonly network = computed(() => this.store.networkById(this.networkId()));
  readonly scenario = computed(() => this.store.scenarioById(this.scenarioId()));

  /** The scenario's own replication count - what an empty override field will use. */
  readonly scenarioReplications = computed(() => this.scenario()?.numReplications ?? 100);

  /**
   * Placeholders, as strings.
   *
   * `strictTemplates` types an input's `placeholder` as `string`, and these are the values an empty
   * field will fall back to - which is the point of showing them: blank means "use the scenario's",
   * not "zero".
   */
  readonly replicationsPlaceholder = computed(() => String(this.scenarioReplications()));

  readonly seedPlaceholder = computed(() => {
    const seed = this.scenario()?.seed;
    // A scenario with no seed draws a fresh one per run and records it, which is what makes a
    // completed run replayable even though it was not pinned in advance.
    return seed === null || seed === undefined ? 'drawn and recorded' : String(seed);
  });

  /**
   * What the job will actually execute: twice the disrupted count - except on a baseline
   * run, which has no disruption to pair against and runs exactly N (FR-17).
   */
  readonly totalReplications = computed(() => {
    const asked = this.replications() ?? this.scenarioReplications();
    return this.baselineChosen() ? asked : asked * 2;
  });

  /** The chosen network's clock, spelled out - "1 day". */
  readonly periodLabel = computed(() => {
    const network = this.network();
    return network ? formatUnits(network.periodLength) : null;
  });

  /** The horizon in wall-clock terms, so "52 periods" is not the only thing on offer. */
  readonly horizonLabel = computed(() => {
    const network = this.network();
    if (!network) {
      return null;
    }
    return `${network.horizonPeriods} × ${formatUnits(network.periodLength)}`;
  });

  /** A scenario with no events would run, and would measure nothing. */
  readonly scenarioIsEmpty = computed(() => (this.scenario()?.eventCount ?? 0) === 0);

  readonly canSubmit = computed(
    () =>
      this.networkId() !== null &&
      this.scenarioChoice() !== null &&
      !this.store.submitting() &&
      !this.store.running(),
  );

  onScenarioChoice(choice: Id | 'baseline' | null): void {
    this.scenarioChoice.set(choice);
  }

  readonly jobStatus = computed(() => this.store.job()?.status ?? JobStatus.QUEUED);
  readonly jobFailed = computed(() => this.jobStatus() === JobStatus.FAILED);
  readonly jobCancelled = computed(() => this.jobStatus() === JobStatus.CANCELLED);

  constructor() {
    // `allowSignalWrites` because `RunLauncherStore.load` sets its state signals synchronously - the
    // same defect the results dashboard carried. Here `load` is not `async`, so Angular's `NG0600`
    // propagated out of the effect into the `ErrorHandler` instead of vanishing into a rejected
    // promise: a loud version of the same silence, and the launcher never listed a network.
    effect(
      () => {
        const projectId = this.projectId();
        if (projectId !== null && this.store.projectId() !== projectId) {
          this.store.load(projectId);
        }
      },
      { allowSignalWrites: true },
    );

    // The run exists from submission, but there is nothing to read until the job succeeds - so the
    // navigation waits for DONE rather than firing on the 202. A cancelled or failed job stays on
    // this page with its reason showing, which is where the researcher can act on it.
    effect(() => {
      const runId = this.store.completedRunId();
      const projectId = this.projectId();
      if (runId !== null && projectId !== null) {
        void this.router.navigate(['/projects', projectId, 'simulations', runId]);
      }
    });
  }

  submit(): void {
    const networkId = this.networkId();
    if (networkId === null || this.scenarioChoice() === null) {
      return;
    }
    // A null scenario id here is the explicitly chosen baseline run of FR-17, never an untouched
    // form - the guard above separates the two.
    void this.store.submit(networkId, this.scenarioId(), this.params());
  }

  cancel(): void {
    void this.store.cancel();
  }

  reset(): void {
    this.store.reset();
  }

  /** Link to the run that was accepted, whatever became of the job. */
  runLink(runId: Id): (string | number)[] {
    return ['/projects', this.projectId() ?? 0, 'simulations', runId];
  }

  /**
   * The overrides, with every untouched field omitted.
   *
   * Omitted is not zero: the server reads a missing field as "not stated" and falls back to the
   * scenario and then to its own default. Sending `replications: 0` would be a validation
   * error, and sending `demandNoiseCv: 0` explicitly would be indistinguishable from the default -
   * harmless here, but the rule is worth keeping uniform.
   */
  private params(): SimulationParamsRequest {
    const params: Record<string, unknown> = {};
    const put = (key: string, value: number | null) => {
      if (value !== null && Number.isFinite(value)) {
        params[key] = value;
      }
    };
    put('replications', this.replications());
    put('seed', this.seed());
    put('demandNoiseCv', this.demandNoiseCv());
    put('timingJitterPeriods', this.timingJitterPeriods());
    if (!this.includeRandomFailures()) {
      params['includeRandomFailures'] = false;
    }
    if (this.baselineSuppressesFailures()) {
      params['baselineSuppressesFailures'] = true;
    }
    return params as SimulationParamsRequest;
  }
}
