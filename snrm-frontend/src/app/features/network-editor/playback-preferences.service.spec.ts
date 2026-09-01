import { TestBed } from '@angular/core/testing';

import { defaultSpeedFor } from './playback-clock';
import { PlaybackPreferencesService } from './playback-preferences.service';

const KEY = PlaybackPreferencesService.STORAGE_KEY;

/** A fresh service, constructed after whatever the test put in storage. */
function freshService(): PlaybackPreferencesService {
  TestBed.configureTestingModule({});
  return TestBed.inject(PlaybackPreferencesService);
}

/** What is actually in the key right now. */
function stored(): unknown {
  const raw = localStorage.getItem(KEY);
  return raw === null ? null : JSON.parse(raw);
}

describe('PlaybackPreferencesService', () => {
  beforeEach(() => localStorage.removeItem(KEY));
  afterEach(() => localStorage.removeItem(KEY));

  it('uses the key the design names', () => {
    expect(KEY).toBe('snrm.playbackSpeed');
  });

  describe('with nothing remembered', () => {
    it('falls back to the horizon-derived default', () => {
      const prefs = freshService();
      expect(prefs.speedFor(1, 52)).toBe(defaultSpeedFor(52));
      expect(prefs.speedFor(1, 365)).toBe(defaultSpeedFor(365));
      expect(prefs.hasPick(1)).toBeFalse();
    });

    it('writes nothing to storage until a speed is picked', () => {
      const prefs = freshService();
      prefs.speedFor(1, 52);
      expect(localStorage.getItem(KEY)).toBeNull();
    });

    it('follows a horizon change, because a default is an answer rather than a stored copy', () => {
      // A time-base change moves the default. Nothing was remembered, so nothing pins it.
      const prefs = freshService();
      expect(prefs.speedFor(7, 30)).toBe(1);
      expect(prefs.speedFor(7, 365)).toBe(10);
    });
  });

  describe('setSpeed', () => {
    it('remembers a pick per network and persists it immediately', () => {
      const prefs = freshService();
      prefs.setSpeed(4, 10);

      expect(prefs.speedFor(4, 52)).toBe(10);
      expect(prefs.hasPick(4)).toBeTrue();
      expect(stored()).toEqual({ '4': 10 });
    });

    it('keeps networks apart', () => {
      const prefs = freshService();
      prefs.setSpeed(4, 10);
      prefs.setSpeed(9, 0.5);

      expect(prefs.speedFor(4, 52)).toBe(10);
      expect(prefs.speedFor(9, 52)).toBe(0.5);
      // Network 5 has no pick, so it still gets the horizon-derived default.
      expect(prefs.speedFor(5, 52)).toBe(2);
      expect(stored()).toEqual({ '4': 10, '9': 0.5 });
    });

    it('survives a reload', () => {
      freshService().setSpeed(4, 5);
      // A second service over the same storage is what the next page load builds.
      TestBed.resetTestingModule();
      expect(freshService().speedFor(4, 52)).toBe(5);
    });

    it('ignores a speed off the ladder rather than clamping it', () => {
      const prefs = freshService();
      prefs.setSpeed(4, 5);
      prefs.setSpeed(4, 3);

      expect(prefs.speedFor(4, 52)).toBe(5);
      expect(stored()).toEqual({ '4': 5 });
    });

    it('forgets a pick back to the default', () => {
      const prefs = freshService();
      prefs.setSpeed(4, 20);
      prefs.forget(4);

      expect(prefs.hasPick(4)).toBeFalse();
      expect(prefs.speedFor(4, 52)).toBe(2);
      expect(stored()).toEqual({});
    });
  });

  describe('reading a key this build did not write', () => {
    it('discards a speed that is not on the current ladder', () => {
      // A stale key from a build whose ladder had other members must not pin playback to a speed
      // there is no control for.
      localStorage.setItem(KEY, JSON.stringify({ '4': 3, '5': 2 }));
      const prefs = freshService();

      expect(prefs.hasPick(4)).toBeFalse();
      expect(prefs.speedFor(4, 52)).toBe(2);
      expect(prefs.speedFor(5, 52)).toBe(2);
      expect(prefs.hasPick(5)).toBeTrue();
    });

    it('discards entries of the wrong type', () => {
      localStorage.setItem(KEY, JSON.stringify({ '1': '2', '2': null, '3': { speed: 2 }, '4': 5 }));
      const prefs = freshService();

      expect(prefs.hasPick(1)).toBeFalse();
      expect(prefs.hasPick(2)).toBeFalse();
      expect(prefs.hasPick(3)).toBeFalse();
      expect(prefs.speedFor(4, 52)).toBe(5);
    });

    it('shrugs off malformed JSON and non-object payloads', () => {
      localStorage.setItem(KEY, '{not json');
      expect(freshService().speedFor(4, 52)).toBe(2);

      TestBed.resetTestingModule();
      localStorage.setItem(KEY, JSON.stringify([2, 5]));
      expect(freshService().speedFor(4, 52)).toBe(2);

      TestBed.resetTestingModule();
      localStorage.setItem(KEY, JSON.stringify('2'));
      expect(freshService().speedFor(4, 52)).toBe(2);
    });
  });

  describe('when storage is denied', () => {
    it('still constructs and still answers, when reading throws', () => {
      spyOn(Storage.prototype, 'getItem').and.throwError('SecurityError');
      const prefs = freshService();
      expect(prefs.speedFor(4, 52)).toBe(2);
    });

    it('degrades to in-memory when writing throws - never into the dialog', () => {
      // Private mode, disabled cookies, or a full quota. The row must keep working for the session.
      spyOn(Storage.prototype, 'setItem').and.throwError('QuotaExceededError');
      const prefs = freshService();

      expect(() => prefs.setSpeed(4, 10)).not.toThrow();
      expect(prefs.speedFor(4, 52)).toBe(10);
      expect(localStorage.getItem(KEY)).toBeNull();
    });
  });
});
