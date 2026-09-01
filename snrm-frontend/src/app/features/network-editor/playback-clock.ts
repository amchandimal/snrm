import { formatUnits } from '../../core/metric-display';
import { Duration } from '../../core/models';

/**
 * The arithmetic of visual playback (FR-18).
 *
 * Free of Angular and of the DOM, like `core/time-units.ts`, `core/metric-display.ts` and
 * `features/simulations/curve-geometry.ts`. The reason is the same one the charting code
 * gives: the clock arithmetic is the part that has to be right, and a pure function is the
 * only version of it that can be tested exhaustively. `playback-clock.spec.ts` pins every rule
 * below; `playback.store.ts` owns the `requestAnimationFrame` loop that calls into it.
 *
 * **Nothing here touches a simulated number.** Playback replays a completed run's persisted series
 * at a pace the viewer chose; the pace is a property of the viewing, not of the run (identical
 * inputs reproduce identical outputs, and a speed setting is not an input).
 */

/**
 * Playback speeds, in simulation periods per real-world second (FR-18).
 *
 * The fractional entries are ordinary members, not a special case: a 10-period run at 1 period per
 * second is over in ten seconds, which is too fast to read, and half speed is the honest way to say
 * so. Every value is exactly representable in binary floating point, so {@link isLadderSpeed} can
 * compare with `===` and a value round-tripped through `localStorage` still matches.
 */
export const SPEED_LADDER: readonly number[] = [0.5, 1, 2, 5, 10, 20];

/**
 * How long a whole run should ideally take to play, in seconds.
 *
 * Thirty seconds is long enough to watch a disruption open and close and short enough to sit
 * through twice. {@link defaultSpeedFor} aims at it; the ladder is coarse, so what it actually
 * lands on is roughly 25–40 s for the horizons researchers use (52, 104, 365).
 */
export const TARGET_RUN_SECONDS = 30;

/**
 * The speed a horizon with no derived answer opens on - one period per real-world second.
 *
 * Reached only when the horizon is absent or nonsensical, which is also when playback is disabled,
 * so this is a well-formed value rather than a meaningful choice.
 */
export const NEUTRAL_SPEED = 1;

/**
 * Longest frame delta the clock will honour, in seconds.
 *
 * A backgrounded tab stops receiving animation frames; the first frame after refocus can carry a
 * delta of minutes. Advancing by it would make the run leap - often straight past the end - which
 * looks exactly like a bug and loses the part of the run the viewer left to go and look something
 * up. Clamping means playback *resumes* from where it was rather than catching up, which is what a
 * replay of a finished computation should do: there is no live thing to stay in sync with.
 *
 * A quarter-second also covers an ordinary hitch - a layout on a large canvas, a garbage collection
 * - without visibly stalling, since it is longer than any frame a healthy renderer produces.
 */
export const MAX_FRAME_DT = 0.25;

/** Where the clock got to, and whether it reached the end of the horizon. */
export interface ClockAdvance {
  /** Fractional period position, in [0, horizonPeriods]. */
  readonly simTime: number;
  /** True once the clock has reached the horizon. The caller stops the loop; it does not wrap. */
  readonly finished: boolean;
}

/**
 * The ladder entry that plays a run of `horizonPeriods` closest to {@link TARGET_RUN_SECONDS}.
 *
 * **"Closest" is measured as a ratio, not as a difference**, because the ladder is geometric and
 * the quantity being judged is a duration. Playing a run in half the ideal time is exactly as wrong
 * as playing it in twice the ideal time, and a linear distance does not say that: a 104-period run
 * is 52 s at speed 2 and 21 s at speed 5, and the linear rule prefers the 52 s - nearly double the
 * target - because 2 is numerically nearer to 3.47 than 5 is. So the entry minimising
 * `|ln(speed / ideal)|` wins, and an exact tie (the geometric mean of two adjacent entries) goes to
 * the **slower** of the two: too slow is a wait, too fast is data the viewer never sees.
 *
 * ```text
 * ideal = horizonPeriods / TARGET_RUN_SECONDS
 * 52  -> 2   (26 s)      104 -> 5   (21 s)
 * 365 -> 10  (37 s)      10  -> 0.5 (20 s)
 * ```
 */
export function defaultSpeedFor(horizonPeriods: number): number {
  if (!Number.isFinite(horizonPeriods) || horizonPeriods <= 0) {
    return NEUTRAL_SPEED;
  }
  const ideal = horizonPeriods / TARGET_RUN_SECONDS;
  let best = NEUTRAL_SPEED;
  let bestDistance = Number.POSITIVE_INFINITY;
  // Slowest first, replacing only on a strictly closer entry - which is what settles ties slower.
  for (const speed of SPEED_LADDER) {
    const distance = Math.abs(Math.log(speed / ideal));
    if (distance < bestDistance) {
      bestDistance = distance;
      best = speed;
    }
  }
  return best;
}

/**
 * Whether a value is a speed this build offers.
 *
 * The guard for everything that did not come from the ladder itself: a `localStorage` entry written
 * by an older build whose ladder differed, a hand-edited one, a `<select>` value that failed to
 * parse. An unrecognised speed is discarded rather than clamped - a stored 3 is not evidence that
 * the viewer wanted 2 - and the horizon-derived default takes over.
 */
export function isLadderSpeed(value: unknown): value is number {
  return typeof value === 'number' && SPEED_LADDER.includes(value);
}

/**
 * One frame of the playback clock.
 *
 * **Advanced by timestamp delta, never by frame count**: a 144 Hz monitor and a 30 Hz one
 * must play the same run in the same wall-clock time, and a fixed per-frame increment makes the
 * playback speed a property of the viewer's hardware. `dtSeconds` is clamped to
 * {@link MAX_FRAME_DT} at the top and to zero at the bottom - some browsers hand a rAF callback a
 * timestamp fractionally behind the previous one after a tab switch, and a negative delta would run
 * the clock backwards.
 *
 * The accumulator is fractional on purpose. Rounding to whole periods per frame would quantise the
 * pace to the frame rate; what is *rendered* is quantised, by {@link periodOf}, because the engine
 * is discrete-time and an interpolated inventory level would be data that does not exist.
 */
export function advanceClock(
  simTime: number,
  dtSeconds: number,
  speed: number,
  horizonPeriods: number,
): ClockAdvance {
  const horizon = Number.isFinite(horizonPeriods) ? Math.max(0, horizonPeriods) : 0;
  const from = Number.isFinite(simTime) ? Math.max(0, simTime) : 0;
  const dt = Number.isFinite(dtSeconds) ? Math.min(Math.max(dtSeconds, 0), MAX_FRAME_DT) : 0;
  const pace = Number.isFinite(speed) && speed > 0 ? speed : 0;

  const advanced = from + dt * pace;
  return advanced >= horizon
    ? { simTime: horizon, finished: true }
    : { simTime: advanced, finished: false };
}

/**
 * The `RUN_TIMESERIES` index a clock value is showing - floor, then clamped to `lastPeriod`.
 *
 * Floor rather than round because a period is a half-open interval: the clock is "in" period 3 from
 * the instant it passes 3 until it reaches 4, and rounding would show period 4 for the second half
 * of period 3's screen time.
 *
 * The clamp is what a finished run rests on. {@link advanceClock} stops at `horizonPeriods`, which
 * is one past the last index, so without it the final frame would index off the end of the series
 * and the run would end on a blank canvas rather than on its last period.
 */
export function periodOf(simTime: number, lastPeriod: number): number {
  const ceiling = Number.isFinite(lastPeriod) ? Math.max(0, Math.floor(lastPeriod)) : 0;
  if (!Number.isFinite(simTime) || simTime <= 0) {
    return 0;
  }
  return Math.min(ceiling, Math.floor(simTime));
}

/**
 * What a speed means in this network's own terms (FR-18).
 *
 * > "One period (1 day) plays in 0.5 s; a full run (52 periods) plays in ~26 s."
 *
 * A bare "2 periods / second" is not readable without the clock: two periods is two seconds of
 * story on a network stepping in 1 day and four hours of it on one stepping in 2 hours. Both halves
 * are here for the same reason `formatTimeValued` keeps both of its - the per-period
 * figure is what the eye will track, and the whole-run figure is the one that decides whether the
 * setting is usable at all.
 *
 * The period figure is exact and the run figure carries `~`: the run total is the sum of however
 * many frame deltas the browser delivers, which lands near the figure rather than on it.
 */
export function paceLine(speed: number, periodLength: Duration, horizonPeriods: number): string {
  const perPeriod = speed > 0 ? 1 / speed : 0;
  const periods = Math.max(0, Math.floor(horizonPeriods));
  return (
    `One period (${formatUnits(periodLength)}) plays in ${formatSeconds(perPeriod)} s; ` +
    `a full run (${periods} ${periods === 1 ? 'period' : 'periods'}) ` +
    `plays in ~${formatSeconds(perPeriod * periods)} s.`
  );
}

/**
 * Seconds as the pace line writes them: one decimal below 10 s, whole above.
 *
 * A tenth of a second is the finest difference worth stating about something a person is watching,
 * and past ten seconds the decimal is noise - "26 s" is the useful figure, not "26.0 s". Trailing
 * zeroes are dropped, so 1 s reads "1" rather than "1.0".
 */
function formatSeconds(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return '0';
  }
  return seconds < 10 ? String(Number(seconds.toFixed(1))) : String(Math.round(seconds));
}
