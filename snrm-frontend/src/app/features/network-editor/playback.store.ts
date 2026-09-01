import {
  Injectable,
  NgZone,
  OnDestroy,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiService } from '../../core/api.service';
// The two sentences moved to `core/` when the results dashboard grew element charts of its own
// (FR-22) and had to say the same thing about the same run. The strings are unchanged - see
// `core/element-series.ts` - which is why `playback.store.spec.ts` still pins them literally.
import {
  ELEMENT_SERIES_UNAVAILABLE,
  ELEMENT_SERIES_UNREADABLE,
  elementSeriesPath,
} from '../../core/element-series';
import { Duration, ElementTimeseries, SimulationResults } from '../../core/models';
import { EditorRunStore } from './editor-run.store';
import { PlaybackElements, indexElements } from './playback-channels';
import { NEUTRAL_SPEED, advanceClock, paceLine, periodOf } from './playback-clock';
import { PlaybackPreferencesService } from './playback-preferences.service';

/**
 * The playback clock of FR-18.
 *
 * > "The clock is a single `requestAnimationFrame` loop advanced by timestamp delta
 * > (frame-rate independent; a backgrounded tab pauses and resumes without leaping), owned by one
 * > playback store - the `JobPollingService` doctrine applied to animation."
 *
 * ## One loop, owned here
 *
 * `JobPollingService` exists because no component may implement its own polling loop;
 * this store is the same rule applied to animation. The canvas, the transport bar, the element
 * inspector and the network dashboard all read the same {@link currentPeriod}, so they cannot drift
 * a period apart, and there is exactly one `requestAnimationFrame` chain to cancel on the way out.
 * The arithmetic itself is in `playback-clock.ts`, pure and specced; this file owns only the loop,
 * the transport and the reactive resets.
 *
 * ## Why `simTime` is a field and not a signal
 *
 * The clock moves 60 times a second and the canvas has something new to draw at most once per
 * *period* - a period is a fifth of a second at the fastest speed on the ladder, and two seconds at
 * the slowest. So the fractional position is a plain private field, and {@link currentPeriod} - the
 * `RUN_TIMESERIES` index - is the signal, written only when the floor actually changes. A signal
 * per frame would schedule change detection 60 times a second to redraw the same period, which is
 * the cost this design exists to avoid. The loop also runs **outside** the Angular zone for the
 * same reason, re-entering it only for that one write.
 *
 * That is also why playback is **stepwise** rather than interpolated: the engine is
 * discrete-time, and an inventory level between period 3 and period 4 is a number no
 * replication produced. Smoothing it would be drawing data that does not exist.
 *
 * ## The run's clock, never the network's
 *
 * {@link periodLength} and {@link horizonPeriods} come from the **report**, not from
 * `NetworkEditorStore.network()`. A run carries the period it was evaluated against
 * (`SimulationRun.periodLength`) precisely so its numbers stay readable afterwards; reading the live
 * network's clock would let a time-base change on a forked variant silently relabel an old run's
 * playback as hours when it was days.
 *
 * The horizon is the **shorter** of what the run declares and what its series actually carry. A
 * `QUEUED` or `RUNNING` run answers `GET /simulations/{runId}` with its record and empty lists,
 * so the declared horizon alone would offer a scrub bar over periods that do not exist
 * yet; a horizon of zero disables playback rather than presenting an empty transport.
 *
 * ## The element series, and why `available: false` is not an empty series
 *
 * The clock alone animates nothing: what moves on the canvas is the **per-element series**,
 * fetched once per report from `GET /simulations/{runId}/timeseries/elements`. That
 * endpoint's `available` flag is the field to branch on - a run that predates
 * `V9__element_timeseries.sql`, or one submitted with `recordElementTimeseries: false`, answers
 * `false` with empty lists, and so does one that is not DONE.
 *
 * **A run with no element detail keeps its playback.** The clock, the transport, the scrub bar and
 * the network-wide performance curve all read `RUN_TIMESERIES`, which every completed run has; only
 * the canvas channels go dark, and the transport bar says so in a line rather than leaving a viewer
 * to wonder why nothing moves. Disabling playback outright would withdraw a working feature because
 * a newer one has no data.
 *
 * Session-scoped, provided on the editor route beside the other editor stores: a clock position
 * belongs to the session watching it. The **speed** deliberately is not - it lives in the root
 * {@link PlaybackPreferencesService}, because a preference has to outlive the session that set it.
 */
@Injectable()
export class PlaybackStore implements OnDestroy {
  private readonly runs = inject(EditorRunStore);
  private readonly prefs = inject(PlaybackPreferencesService);
  private readonly api = inject(ApiService);
  private readonly zone = inject(NgZone);

  // ------------------------------------------------------------------- what there is to play

  /**
   * How many periods this run has to show: the shorter of what it declares and what it delivered.
   *
   * Zero means there is nothing to play - no report, or a report whose series are still empty.
   */
  readonly horizonPeriods = computed(() => {
    const report = this.runs.report();
    if (!report) {
      return 0;
    }
    return Math.max(0, Math.min(report.run.horizonPeriods, report.timeseries.length));
  });

  /** True when a completed run is loaded and carries periods to play. */
  readonly enabled = computed(() => this.runs.report() !== null && this.horizonPeriods() > 0);

  /** Highest valid `RUN_TIMESERIES` index. Zero when there is nothing to play. */
  readonly lastPeriod = computed(() => Math.max(0, this.horizonPeriods() - 1));

  /** The **run's** period length - the clock every playback reading is stated in. */
  readonly periodLength = computed<Duration | null>(
    () => this.runs.reportRun()?.periodLength ?? null,
  );

  // -------------------------------------------------------------------- the element series

  private readonly _elements = signal<PlaybackElements | null>(null);
  private readonly _seriesAvailable = signal(false);
  private readonly _elementsLoading = signal(false);
  private readonly _elementNote = signal<string | null>(null);

  /**
   * This run's per-element series, keyed by element id, or null when there is none to animate.
   *
   * Null covers all three of "no report", "the run recorded nothing" and "the read failed" -
   * deliberately, because the canvas does the same thing in all three: it leaves the elements in
   * their ordinary styling. What differs between them is what the transport bar *says*, which is
   * {@link elementNote}'s job.
   */
  readonly elements = this._elements.asReadonly();

  /** Whether this run recorded a per-element series at all (`available`). */
  readonly seriesAvailable = this._seriesAvailable.asReadonly();

  /** True while the element read is in flight - the note waits for it rather than flashing. */
  readonly elementsLoading = this._elementsLoading.asReadonly();

  /**
   * The one line the transport bar shows when playback works but the canvas cannot animate, or null.
   *
   * Two different sentences, because they are two different situations and only one of them is
   * anybody's fault: a run recorded before the element series existed will never have one, and a
   * read that failed might succeed on the next report.
   */
  readonly elementNote = computed(() => {
    if (!this.enabled() || this._elementsLoading() || this._seriesAvailable()) {
      return null;
    }
    return this._elementNote();
  });

  // ------------------------------------------------------------------------------ transport

  private readonly _playing = signal(false);
  private readonly _currentPeriod = signal(0);

  readonly playing = this._playing.asReadonly();

  /** The period on screen - written at most once per period, never per frame. */
  readonly currentPeriod = this._currentPeriod.asReadonly();

  /**
   * The speed this device last picked for this network, or the horizon-derived default.
   *
   * A `computed` over the preferences signal, so {@link setSpeed} takes effect without the loop
   * being told: every frame reads this, which is what makes a mid-play change land on the next
   * frame with no jump in position.
   */
  readonly speed = computed(() => {
    const run = this.runs.reportRun();
    return run ? this.prefs.speedFor(run.networkId, this.horizonPeriods()) : NEUTRAL_SPEED;
  });

  /** The pace line, in the run's own clock. Null while there is nothing to play. */
  readonly pace = computed(() => {
    const periodLength = this.periodLength();
    return periodLength === null
      ? null
      : paceLine(this.speed(), periodLength, this.horizonPeriods());
  });

  // -------------------------------------------------------------------------------- the loop

  /**
   * Fractional period position. **Not a signal** - see the class note; nothing renders per frame.
   */
  private simTime = 0;

  /** The pending `requestAnimationFrame` handle, or null when the loop is not running. */
  private frame: number | null = null;

  /**
   * Timestamp of the previous frame, or null when the next frame must re-seed the delta.
   *
   * Nulled by {@link pause} so that resuming after a minute of being paused advances by one frame's
   * worth of time rather than by a minute - the same protection {@link advanceClock}'s clamp gives
   * a backgrounded tab, applied to the case the clamp cannot see.
   */
  private lastTimestamp: number | null = null;

  /** The report the transport is currently positioned in, so a new one can be recognised. */
  private seenReport: SimulationResults | null | undefined = undefined;

  /** The auto-play token last acted on, so one completed run starts playback exactly once. */
  private seenAutoPlay: number;

  /**
   * Which element read is current. Bumped on every report change, so a slow answer for a report the
   * viewer has already left cannot land on top of the one they are watching.
   */
  private elementRequest = 0;

  constructor() {
    // Seeded rather than assumed zero: this store is provided on the editor route but instantiated
    // on first injection, which need not be before the first run of the session settles. A token
    // already past means "that run started playing without us", not "start now".
    this.seenAutoPlay = untracked(() => this.runs.autoPlayToken());

    // One effect for both signals, because a completed run changes both in the same flush and the
    // order they are handled in has to be fixed: rewind first, then play. Two effects would leave it
    // to Angular's scheduling, and the losing order pauses the animation it was asked to start.
    effect(
      () => {
        const report = this.runs.report();
        const autoPlay = this.runs.autoPlayToken();
        untracked(() => {
          // A different report is a different clock: a run of another network, another horizon,
          // another period length. Rewinding is the only honest answer - leaving the position would
          // show period 40 of a run that has 30. A report going null (a new submission clears it)
          // lands here too and takes the same path, which is why there is no separate teardown.
          if (report !== this.seenReport) {
            this.seenReport = report;
            this.pause();
            this.seek(0);
            this.loadElements(report);
          }
          // "Click Run, watch it simulate" (FR-18). Only on a bump, so re-emitting the same report
          // does not restart the animation, and only when there is something to play - a run whose
          // series are empty leaves the transport where it is rather than playing nothing.
          if (autoPlay !== this.seenAutoPlay) {
            this.seenAutoPlay = autoPlay;
            if (this.enabled()) {
              this.seek(0);
              this.play();
            }
          }
        });
      },
      { allowSignalWrites: true },
    );
  }

  /**
   * Fetch this run's per-element series (FR-18) - once per report, never per period.
   *
   * The whole horizon arrives in one response and stays in memory for as long as the report does,
   * which is what lets the clock step at up to twenty periods a second without a request in sight.
   * `horizon × (nodes + links)` numbers is exactly why this is kept out of
   * `GET /simulations/{runId}` - so it is asked for only where it is drawn.
   *
   * **Every outcome is a state, and none of them is an error banner.** A run with no series is a run
   * that answered a different question, not a failure, and a failed read costs the canvas
   * channels and nothing else - the transport, the clock and the report beside it keep working. The
   * run panel owns the errors that matter to a run; playback does not compete with it.
   */
  private loadElements(report: SimulationResults | null): void {
    const request = ++this.elementRequest;
    this._elements.set(null);
    this._seriesAvailable.set(false);
    this._elementNote.set(null);
    this._elementsLoading.set(false);
    if (!report || report.timeseries.length === 0) {
      // No run, or one still executing - its `available` would be false anyway, so the
      // read is skipped rather than spent to be told so.
      return;
    }
    this._elementsLoading.set(true);
    void firstValueFrom(this.api.get<ElementTimeseries>(elementSeriesPath(report.run.id)))
      .then((series) => {
        if (request !== this.elementRequest) {
          return;
        }
        this._elementsLoading.set(false);
        this._seriesAvailable.set(series.available === true);
        this._elements.set(series.available ? indexElements(series) : null);
        this._elementNote.set(series.available ? null : ELEMENT_SERIES_UNAVAILABLE);
      })
      .catch(() => {
        if (request !== this.elementRequest) {
          return;
        }
        this._elementsLoading.set(false);
        this._seriesAvailable.set(false);
        this._elements.set(null);
        this._elementNote.set(ELEMENT_SERIES_UNREADABLE);
      });
  }

  /**
   * Start playing, restarting from the beginning if the clock is already at the end.
   *
   * The restart is the reason a finished run's play button does not need to be a separate control:
   * the clock rests **on** the horizon when it finishes (`simTime === horizonPeriods`, one past the
   * last index), so "play again" is unambiguous.
   */
  play(): void {
    if (!this.enabled() || this._playing()) {
      return;
    }
    if (this.simTime >= this.horizonPeriods()) {
      this.seek(0);
    }
    this._playing.set(true);
    this.lastTimestamp = null;
    this.requestFrame();
  }

  /** Stop the clock where it is. The next {@link play} re-seeds the delta rather than catching up. */
  pause(): void {
    this.cancelFrame();
    this._playing.set(false);
  }

  toggle(): void {
    if (this._playing()) {
      this.pause();
    } else {
      this.play();
    }
  }

  /**
   * Step one period on, pausing first.
   *
   * Stepping is defined on the **period index**, not on the fractional clock: stepping forward from
   * a run paused three-quarters of the way through period 3 lands on period 4, and stepping back
   * lands on period 2 - symmetric about the period the viewer can actually see.
   */
  stepForward(): void {
    this.pause();
    this.seek(this._currentPeriod() + 1);
  }

  /** Step one period back, pausing first. See {@link stepForward}. */
  stepBack(): void {
    this.pause();
    this.seek(this._currentPeriod() - 1);
  }

  /**
   * Jump to a period, clamped into the run (the scrub slider's write path).
   *
   * Does not stop playback: dragging the scrubber while a run plays should move the clock and leave
   * it running, and the frame delta is measured against wall time, so the next frame carries on
   * from wherever the seek put it.
   */
  seek(period: number): void {
    const target = Number.isFinite(period) ? Math.round(period) : 0;
    const clamped = Math.min(Math.max(0, target), this.lastPeriod());
    this.simTime = clamped;
    this._currentPeriod.set(clamped);
  }

  /** Rewind to the first period and play - what the ⟲ control means on a transport bar. */
  restart(): void {
    this.seek(0);
    this.play();
  }

  /**
   * Change the playback speed.
   *
   * **Delegates to the preference service - one write path.** The dialog's select and the Stage-4
   * transport bar both land here, so a speed can never be applied to the running clock without also
   * being remembered for this network, and the two controls cannot disagree about what is set.
   *
   * Takes effect on the next frame and moves nothing: {@link speed} is read inside the loop, and
   * `simTime` is untouched.
   */
  setSpeed(speed: number): void {
    const run = this.runs.reportRun();
    if (run) {
      this.prefs.setSpeed(run.networkId, speed);
    }
  }

  // ------------------------------------------------------------------------------- internals

  /**
   * One frame: advance by the elapsed wall time, publish the period if it changed, ask for another.
   *
   * The first frame after a play or a resume has no previous timestamp and therefore advances by
   * nothing - it exists to establish the baseline the next delta is measured from.
   */
  private onFrame(timestamp: number): void {
    this.frame = null;
    if (!this._playing()) {
      return;
    }
    const previous = this.lastTimestamp;
    this.lastTimestamp = timestamp;
    const dtSeconds = previous === null ? 0 : (timestamp - previous) / 1000;

    const advance = advanceClock(
      this.simTime,
      dtSeconds,
      this.speed(),
      this.horizonPeriods(),
    );
    this.simTime = advance.simTime;
    this.publishPeriod();

    if (advance.finished) {
      // Rest on the horizon rather than wrapping: a run that ends is a finding, and looping it
      // would make the end invisible. `simTime` stays one past the last index, which is what
      // `play()` reads to know it should restart.
      this.zone.run(() => this._playing.set(false));
      this.lastTimestamp = null;
      return;
    }
    this.requestFrame();
  }

  /** The one place the period signal is written - and only when it actually changed. */
  private publishPeriod(): void {
    const period = periodOf(this.simTime, this.lastPeriod());
    if (period !== this._currentPeriod()) {
      // Back into the zone for this write alone: everything else the loop does is invisible.
      this.zone.run(() => this._currentPeriod.set(period));
    }
  }

  /**
   * Schedules the next frame **outside** the Angular zone.
   *
   * Inside it, zone.js would treat every frame as an event worth a change-detection pass - 60 a
   * second, to redraw a period that changes five times a second at most.
   */
  private requestFrame(): void {
    this.zone.runOutsideAngular(() => {
      this.frame = window.requestAnimationFrame((timestamp) => this.onFrame(timestamp));
    });
  }

  private cancelFrame(): void {
    if (this.frame !== null) {
      window.cancelAnimationFrame(this.frame);
      this.frame = null;
    }
    this.lastTimestamp = null;
  }

  ngOnDestroy(): void {
    this.cancelFrame();
  }
}
