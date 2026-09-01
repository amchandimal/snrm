import { Duration, TimeUnit } from '../../core/models';
import {
  MAX_FRAME_DT,
  NEUTRAL_SPEED,
  SPEED_LADDER,
  advanceClock,
  defaultSpeedFor,
  isLadderSpeed,
  paceLine,
  periodOf,
} from './playback-clock';

const ONE_DAY: Duration = { value: 1, unit: TimeUnit.DAY };

describe('playback-clock', () => {
  describe('SPEED_LADDER', () => {
    it('is ascending, positive, and exactly representable', () => {
      expect(SPEED_LADDER).toEqual([0.5, 1, 2, 5, 10, 20]);
      SPEED_LADDER.forEach((speed, index) => {
        expect(speed).toBeGreaterThan(0);
        if (index > 0) {
          expect(speed).toBeGreaterThan(SPEED_LADDER[index - 1]);
        }
        // Round-tripping through localStorage must return the identical value, or isLadderSpeed
        // would reject a speed this build itself wrote.
        expect(JSON.parse(JSON.stringify(speed))).toBe(speed);
      });
    });
  });

  describe('defaultSpeedFor', () => {
    it('pins the four horizons the design names', () => {
      expect(defaultSpeedFor(52)).toBe(2);
      expect(defaultSpeedFor(104)).toBe(5);
      expect(defaultSpeedFor(365)).toBe(10);
      expect(defaultSpeedFor(10)).toBe(0.5);
    });

    it('takes the ladder entry nearest in ratio, not in difference', () => {
      // The case the two rules disagree on. 104 / 30 = 3.47 periods per second: linearly 2 is
      // nearer (1.47 away, against 1.53), but 2 plays the run in 52 s where 5 plays it in 21 s,
      // and 21 s is the nearer of those to the 30 s target as a duration.
      expect(defaultSpeedFor(104)).toBe(5);
      expect(104 / 5).toBeCloseTo(20.8, 1);
      expect(104 / 2).toBeCloseTo(52, 1);
    });

    it('lands a 30-period run on exactly one period per second', () => {
      // The 4-echelon test network of MANUAL-TEST.md: 1 DAY / 30.
      expect(defaultSpeedFor(30)).toBe(1);
    });

    it('aims the usual horizons within roughly 20–40 s', () => {
      [30, 52, 104, 365].forEach((horizon) => {
        const seconds = horizon / defaultSpeedFor(horizon);
        expect(seconds).toBeGreaterThanOrEqual(20);
        expect(seconds).toBeLessThanOrEqual(40);
      });
    });

    it('never leaves the ladder, whatever the horizon', () => {
      for (let horizon = 1; horizon <= 2000; horizon++) {
        expect(isLadderSpeed(defaultSpeedFor(horizon))).toBeTrue();
      }
    });

    it('settles a tie on the slower entry', () => {
      // A tie is the geometric mean of two adjacent entries: sqrt(1 x 2) = 1.4142 periods per
      // second, i.e. a horizon of 42.426. Just under it must still be the slower of the pair, and
      // the two sides of the boundary must differ.
      expect(defaultSpeedFor(42)).toBe(1);
      expect(defaultSpeedFor(43)).toBe(2);
    });

    it('falls back to the neutral speed for a horizon there is no answer for', () => {
      expect(defaultSpeedFor(0)).toBe(NEUTRAL_SPEED);
      expect(defaultSpeedFor(-5)).toBe(NEUTRAL_SPEED);
      expect(defaultSpeedFor(Number.NaN)).toBe(NEUTRAL_SPEED);
    });
  });

  describe('isLadderSpeed', () => {
    it('accepts every member, including the fractional one', () => {
      SPEED_LADDER.forEach((speed) => expect(isLadderSpeed(speed)).toBeTrue());
    });

    it('rejects what tampering and stale keys produce', () => {
      // A speed from a build whose ladder differed, a string from a <select> that was not parsed,
      // and the shapes a hand-edited localStorage entry can take.
      expect(isLadderSpeed(3)).toBeFalse();
      expect(isLadderSpeed(0)).toBeFalse();
      expect(isLadderSpeed(-2)).toBeFalse();
      expect(isLadderSpeed(1000)).toBeFalse();
      expect(isLadderSpeed('2')).toBeFalse();
      expect(isLadderSpeed(null)).toBeFalse();
      expect(isLadderSpeed(undefined)).toBeFalse();
      expect(isLadderSpeed(Number.NaN)).toBeFalse();
      expect(isLadderSpeed({ speed: 2 })).toBeFalse();
    });
  });

  describe('advanceClock', () => {
    it('advances by the timestamp delta, not by a frame count', () => {
      // A fifth of a second at 5 periods per second is one period, whether it arrived as one frame
      // or as thirty. Both deltas here are under MAX_FRAME_DT, which is the case this rule is
      // about - the clamp has its own test below and would otherwise mask this one.
      const single = advanceClock(0, 0.2, 5, 52);
      expect(single.simTime).toBeCloseTo(1, 12);
      expect(single.finished).toBeFalse();

      let simTime = 0;
      for (let frame = 0; frame < 30; frame++) {
        simTime = advanceClock(simTime, 0.2 / 30, 5, 52).simTime;
      }
      expect(simTime).toBeCloseTo(1, 10);
    });

    it('accumulates fractionally rather than snapping to whole periods', () => {
      const first = advanceClock(0, 1 / 60, 2, 52);
      expect(first.simTime).toBeCloseTo(0.0333, 4);
      expect(periodOf(first.simTime, 51)).toBe(0);
    });

    it('clamps a background-tab delta so playback resumes instead of leaping', () => {
      // Ten seconds away from the tab at 2 periods per second would be 20 periods. What the viewer
      // must get back is a quarter-second of movement, not twenty periods they never saw.
      const resumed = advanceClock(4, 10, 2, 52);
      expect(resumed.simTime).toBe(4 + MAX_FRAME_DT * 2);
      expect(resumed.finished).toBeFalse();
    });

    it('ignores a delta that runs backwards', () => {
      expect(advanceClock(3, -1, 2, 52)).toEqual({ simTime: 3, finished: false });
      expect(advanceClock(3, Number.NaN, 2, 52)).toEqual({ simTime: 3, finished: false });
    });

    it('stops exactly on the horizon and reports finished', () => {
      const end = advanceClock(51.9, 0.2, 2, 52);
      expect(end).toEqual({ simTime: 52, finished: true });
    });

    it('is finished at once on a horizon of zero', () => {
      expect(advanceClock(0, 0.1, 2, 0)).toEqual({ simTime: 0, finished: true });
    });

    it('holds position when the speed is not usable', () => {
      expect(advanceClock(7, 0.1, 0, 52)).toEqual({ simTime: 7, finished: false });
      expect(advanceClock(7, 0.1, Number.NaN, 52)).toEqual({ simTime: 7, finished: false });
    });
  });

  describe('periodOf', () => {
    it('floors rather than rounds - a period is a half-open interval', () => {
      expect(periodOf(3, 51)).toBe(3);
      expect(periodOf(3.01, 51)).toBe(3);
      expect(periodOf(3.99, 51)).toBe(3);
      expect(periodOf(4, 51)).toBe(4);
    });

    it('rests on the last period when the clock reaches the horizon', () => {
      // advanceClock stops at 52, which is one past the last index. Without the clamp the finished
      // run would index off the end of RUN_TIMESERIES.
      expect(periodOf(52, 51)).toBe(51);
      expect(periodOf(1000, 51)).toBe(51);
    });

    it('never goes below zero', () => {
      expect(periodOf(-1, 51)).toBe(0);
      expect(periodOf(Number.NaN, 51)).toBe(0);
      expect(periodOf(5, -1)).toBe(0);
    });
  });

  describe('paceLine', () => {
    it('reads exactly as the playback panel words it', () => {
      expect(paceLine(2, ONE_DAY, 52)).toBe(
        'One period (1 day) plays in 0.5 s; a full run (52 periods) plays in ~26 s.',
      );
    });

    it('drops the decimal on a whole number of seconds', () => {
      // The 4-echelon network of the manual test: 1 DAY / 30, default speed 1.
      expect(paceLine(1, ONE_DAY, 30)).toBe(
        'One period (1 day) plays in 1 s; a full run (30 periods) plays in ~30 s.',
      );
    });

    it('states the period in the network clock it was given, not in days', () => {
      // A network stepping in 2 hours: the same speed is a completely different amount of story
      // per second, which is the whole reason the line quotes the period.
      expect(paceLine(5, { value: 2, unit: TimeUnit.HOUR }, 624)).toBe(
        'One period (2 hours) plays in 0.2 s; a full run (624 periods) plays in ~125 s.',
      );
    });

    it('keeps one decimal below ten seconds and whole numbers above', () => {
      expect(paceLine(0.5, ONE_DAY, 8)).toBe(
        'One period (1 day) plays in 2 s; a full run (8 periods) plays in ~16 s.',
      );
      expect(paceLine(20, ONE_DAY, 100)).toBe(
        'One period (1 day) plays in 0.1 s; a full run (100 periods) plays in ~5 s.',
      );
    });

    it('says "1 period" for a one-period run', () => {
      expect(paceLine(1, ONE_DAY, 1)).toBe(
        'One period (1 day) plays in 1 s; a full run (1 period) plays in ~1 s.',
      );
    });

    it('states the period value as well as its unit', () => {
      // The slip core/metric-display.ts exists to prevent: a network stepping in 2 DAY
      // carries DAY, and one of its periods is two days.
      expect(paceLine(1, { value: 2, unit: TimeUnit.DAY }, 30)).toContain('One period (2 days)');
    });
  });
});
