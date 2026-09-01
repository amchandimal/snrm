import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';

import {
  Duration,
  Id,
  Network,
  ROUNDING_POLICIES,
  ROUNDING_POLICY_HINT,
  RoundingPolicy,
  TimeBaseRequest,
  TimeUnit,
} from '../../../core/models';
import {
  convertDuration,
  formatDuration,
  formatSpan,
  horizonSeconds,
  periodsForSpan,
} from '../../../core/time-units';
import { UnitValueComponent, UnitChange } from '../../../shared/unit-value/unit-value.component';
import { NetworkEditorStore } from '../network-editor.store';
import { SPEED_LADDER, isLadderSpeed, paceLine } from '../playback-clock';
import { PlaybackPreferencesService } from '../playback-preferences.service';
import { UnitPreferencesService } from '../unit-preferences.service';

/**
 * The network time-settings dialog.
 *
 * A settings dialog on the editor toolbar sets the network's period length, horizon and rounding
 * policy, and offers the 'suggest period' action.
 *
 * ## Why all three fields save together
 *
 * A partial time base is not a thing. Halving the period without touching the horizon halves how
 * much wall-clock time a run covers, and the rounding policy decides whether the change loses any
 * durations at all - so `PUT /networks/{id}/time-base` takes the three together and this dialog is
 * the only place they are set.
 *
 * The **span line** exists because of exactly that trap: 52 periods of 1 day is 52 days, and 52
 * periods of 2 hours is four days and eight hours. The horizon deliberately does not follow a period
 * change on its own - that would silently redefine the study - so the dialog states the consequence
 * and offers the horizon that would preserve it.
 *
 * ## Why applying can end in the fork prompt
 *
 * Results are stated in periods, so redefining the period turns "TTR = 14" from fourteen days into
 * fourteen hours without altering a stored number. The server refuses the change on a network with
 * runs, and the editor answers with the fork-to-variant prompt rather than a retry - the same path
 * every other blocked edit takes.
 *
 * ## Why the playback-speed row is outside that contract
 *
 * The fourth row of this dialog is deliberately **not** a fourth field of the time base (FR-18).
 * The three above it describe the model's clock: they travel together in
 * one `TimeBaseRequest`, they change what the engine computes, and they are refused on a frozen
 * network for exactly that reason. The playback speed describes the *viewing* - how fast a
 * completed run's persisted series are replayed on the canvas - and changes no simulated number
 * (identical inputs reproduce identical outputs, and a pace is not an input).
 *
 * Three consequences follow, and all three are visible in the template. It **saves on change**,
 * through {@link PlaybackPreferencesService} to `localStorage`, in the same immediate way
 * {@link onPeriodUnit} calls `units.remember(...)` outside the Apply flow - so it never enters
 * {@link dirty}, never enables {@link canApply}, and never reaches the API. It is **not disabled by
 * `busy()`**, because nothing it does can conflict with a time-base save in flight. And it stays
 * live on a **frozen** network, where every other control here ends in the fork prompt: playback
 * exists to replay a completed run, so a frozen network is the normal case for this row rather than
 * the exception - the same reasoning that keeps the disruptions panel live.
 *
 * Its pace line reads the **applied** network, not the drafts above: the speed applies to the clock
 * the network actually has, and quoting an unsaved period would describe a network that does not
 * exist yet.
 */
@Component({
  selector: 'app-time-settings-dialog',
  standalone: true,
  imports: [UnitValueComponent],
  templateUrl: './time-settings-dialog.component.html',
  styleUrl: './time-settings-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TimeSettingsDialogComponent {
  readonly network = input.required<Network>();
  readonly busy = input(false);

  readonly applied = output<TimeBaseRequest>();
  readonly cancelled = output<void>();

  private readonly store = inject(NetworkEditorStore);
  private readonly units = inject(UnitPreferencesService);
  /** Root-provided: the pick outlives this dialog, this editor session and this browser tab. */
  private readonly playback = inject(PlaybackPreferencesService);

  readonly policies = ROUNDING_POLICIES;
  readonly policyHints = ROUNDING_POLICY_HINT;
  readonly speedLadder = SPEED_LADDER;

  readonly periodValue = signal(1);
  readonly periodUnit = signal<TimeUnit>('DAY');
  /** Kept as text so a half-typed horizon survives a re-render, like every other draft field. */
  readonly horizonDraft = signal('1');
  readonly policy = signal<RoundingPolicy>('NEAREST');

  readonly suggesting = signal(false);
  /** What the last "suggest period" produced: a period, or the reason there is none. */
  readonly suggestionNote = signal<string | null>(null);

  /** Which network the form was seeded from, so re-rendering does not discard what was typed. */
  private readonly seededFor = signal<Id | null>(null);

  readonly actionError = this.store.actionError;

  constructor() {
    effect(
      () => {
        const network = this.network();
        if (this.seededFor() === network.id) {
          return;
        }
        this.seededFor.set(network.id);
        this.periodValue.set(network.periodLength.value);
        this.periodUnit.set(network.periodLength.unit);
        this.horizonDraft.set(String(network.horizonPeriods));
        this.policy.set(network.roundingPolicy);
        this.suggestionNote.set(null);
      },
      { allowSignalWrites: true },
    );
  }

  readonly period = computed<Duration>(() => ({
    value: this.periodValue(),
    unit: this.periodUnit(),
  }));

  readonly horizon = computed<number | null>(() => {
    const value = Number(this.horizonDraft().trim());
    return Number.isInteger(value) && value >= 1 ? value : null;
  });

  readonly periodValid = computed(() => this.periodValue() > 0);

  readonly canApply = computed(
    () => this.periodValid() && this.horizon() !== null && !this.busy() && this.dirty(),
  );

  readonly dirty = computed(() => {
    const network = this.network();
    return (
      network.periodLength.value !== this.periodValue() ||
      network.periodLength.unit !== this.periodUnit() ||
      network.horizonPeriods !== this.horizon() ||
      network.roundingPolicy !== this.policy()
    );
  });

  /** Wall-clock span of a run as the network stands. */
  readonly currentSpan = computed(() => {
    const network = this.network();
    return formatSpan(horizonSeconds(network.periodLength, network.horizonPeriods));
  });

  /** Wall-clock span the form would produce. */
  readonly newSpan = computed(() => {
    const horizon = this.horizon();
    return horizon === null || !this.periodValid()
      ? null
      : formatSpan(horizonSeconds(this.period(), horizon));
  });

  /** The horizon that would keep the run covering the same wall clock as it does now. */
  readonly matchingHorizon = computed(() => {
    const network = this.network();
    if (!this.periodValid()) {
      return null;
    }
    return periodsForSpan(
      horizonSeconds(network.periodLength, network.horizonPeriods),
      this.period(),
    );
  });

  readonly spanWouldChange = computed(() => {
    const matching = this.matchingHorizon();
    return matching !== null && matching !== this.horizon();
  });

  readonly currentPeriodLabel = computed(() => formatDuration(this.network().periodLength));

  // ------------------------------------------------- playback speed (FR-18)
  // A view preference, not part of the time base. See the class note above for why it sits here and
  // yet takes none of the Apply machinery.

  /**
   * The speed remembered for this network on this device, or the horizon-derived default.
   *
   * Reads the **applied** network rather than the drafts above, so the select shows what is in
   * force now - the drafts describe a time base that may never be applied.
   */
  readonly playbackSpeed = computed(() =>
    this.playback.speedFor(this.network().id, this.network().horizonPeriods),
  );

  /** "One period (1 day) plays in 1 s; a full run (30 periods) plays in ~30 s." */
  readonly playbackPace = computed(() => {
    const network = this.network();
    return paceLine(this.playbackSpeed(), network.periodLength, network.horizonPeriods);
  });

  /** How a ladder entry reads in the select - "2 periods / second". */
  speedLabel(speed: number): string {
    return `${speed} ${speed === 1 ? 'period' : 'periods'} / second`;
  }

  /**
   * Save the pick immediately - there is no Apply for this row.
   *
   * The same shape as {@link onPeriodUnit}'s `units.remember(...)` call: a preference the user just
   * expressed is recorded when they express it. Nothing here touches the drafts, {@link dirty} or
   * the network, so it is inert on a frozen network in the sense that matters - it cannot be
   * refused and can never raise the fork prompt.
   */
  onPlaybackSpeed(value: string): void {
    const speed = Number(value);
    if (isLadderSpeed(speed)) {
      this.playback.setSpeed(this.network().id, speed);
    }
  }

  onPeriodValue(value: number): void {
    this.periodValue.set(value);
  }

  onPeriodUnit(change: UnitChange): void {
    // The period is a duration like any other: picking a coarser unit restates it, never
    // reinterprets it.
    const base: Duration =
      change.pendingValue === null
        ? this.period()
        : { value: change.pendingValue, unit: this.periodUnit() };
    const restated = convertDuration(base, change.unit);
    this.periodValue.set(restated.value);
    this.periodUnit.set(restated.unit);
    this.units.remember(UnitPreferencesService.NETWORK_PERIOD, change.unit);
  }

  onHorizon(text: string): void {
    this.horizonDraft.set(text);
  }

  onPolicy(value: string): void {
    const policy = value as RoundingPolicy;
    if (ROUNDING_POLICIES.includes(policy)) {
      this.policy.set(policy);
    }
  }

  /** Adopt the horizon that preserves the current wall-clock span. */
  matchSpan(): void {
    const matching = this.matchingHorizon();
    if (matching !== null) {
      this.horizonDraft.set(String(matching));
    }
  }

  /**
   * "Suggest period" - the coarsest period keeping every declared duration within 10%.
   *
   * The server computes it, over every duration in the network rather than the ones on screen. It
   * fills the field and nothing more: the researcher still presses Apply, because a period change
   * is a decision about the model, not a correction to it.
   */
  async suggestPeriod(): Promise<void> {
    this.suggesting.set(true);
    this.suggestionNote.set(null);
    try {
      const report = await this.store.refreshTimeValidation();
      if (!report) {
        this.suggestionNote.set('Could not reach the server for a suggestion.');
        return;
      }
      const suggested = report.suggestedPeriod;
      if (!suggested) {
        this.suggestionNote.set(
          'No suggestion: this network declares no positive duration, so nothing constrains the period.',
        );
        return;
      }
      this.periodValue.set(suggested.value);
      this.periodUnit.set(suggested.unit);
      this.suggestionNote.set(
        `Suggested ${formatDuration(suggested)} - the coarsest period that keeps every declared ` +
          'duration within 10%. Check the horizon below before applying.',
      );
    } finally {
      this.suggesting.set(false);
    }
  }

  submit(): void {
    const horizon = this.horizon();
    if (!this.periodValid() || horizon === null) {
      return;
    }
    this.applied.emit({
      periodLength: this.period(),
      horizonPeriods: horizon,
      roundingPolicy: this.policy(),
    });
  }

  onBackdropClick(event: MouseEvent): void {
    if (!this.busy() && event.target === event.currentTarget) {
      this.cancelled.emit();
    }
  }
}
