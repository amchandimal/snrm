import { Injectable, signal, untracked } from '@angular/core';

import { Id } from '../../core/models';
import { defaultSpeedFor, isLadderSpeed } from './playback-clock';

/** Where the per-network picks live between sessions. */
const STORAGE_KEY = 'snrm.playbackSpeed';

/** What the key holds: network id (as a JSON object key) to a ladder speed. */
type StoredSpeeds = Readonly<Record<string, number | undefined>>;

/**
 * The playback speed of FR-18, remembered per network on this device.
 *
 * > "a device-local view preference (the app's one `localStorage` use, keyed per network), saved on
 * > change, never sent to the API, absent from export, and fully usable on a frozen network"
 *
 * ## Why this is not on the network
 *
 * A playback speed changes no simulated number (identical inputs reproduce identical
 * outputs, and a pace is not an input). Putting it on the network would make it a column, a
 * `TimeBaseRequest` field and an export field, all of which are refused once the network is frozen
 * - and a network is frozen *exactly* when playback matters, because playback replays a completed
 * run. A setting that could not be changed on the only networks it applies to would be
 * unusable, so it is not model state at all: it is how this browser chose to watch.
 *
 * ## Why it is stored, and why per network
 *
 * A pace suits a horizon. The same viewer wants 2 periods per second on a 52-period study and 10 on
 * a 365-period one, and re-picking it at the start of every session is the friction that makes
 * people leave the default and squint. Keyed per network rather than globally for the same reason:
 * two networks in a project can step on different clocks, and one remembered speed would have them
 * fight over it. Nothing is stored until the viewer picks something - an absent key means "use the
 * horizon-derived default", which is a live answer that follows a time-base change rather than a
 * stale copy of one.
 *
 * ## Storage discipline
 *
 * This is the first `localStorage` use in the app for a **user preference**; `core/TokenStore` is
 * the only other use of it, and it mirrors the auth session, which is a different kind of thing.
 * The discipline is that file's: every read and every write is wrapped, and denied storage (private
 * mode, disabled cookies, a full quota) degrades to an in-memory signal that works perfectly for
 * the session. Nothing here throws into the dialog - a settings dialog that cannot be opened
 * because a preference could not be saved would be a much worse failure than a preference that does
 * not survive a reload. Anything in the key that is not a current ladder speed is discarded on
 * read, so a stale key written by a build with a different ladder cannot pin playback to a speed
 * this build has no control for.
 *
 * **Deliberately `providedIn: 'root'`**, unlike the route-scoped {@link UnitPreferencesService}
 * beside it. A unit preference is a habit within one modelling session; this one has to outlive the
 * editor session - it is read back on the next visit - and it is shared with the transport bar the
 * canvas grows in Stage 4, which reads the same value the settings dialog writes.
 */
@Injectable({ providedIn: 'root' })
export class PlaybackPreferencesService {
  /** Exposed so a spec - and a future "reset preferences" action - can name the key once. */
  static readonly STORAGE_KEY = STORAGE_KEY;

  /**
   * The picks, in memory. Seeded from storage at construction and written through on every change,
   * so the signal is authoritative for the session even where storage is denied.
   */
  private readonly speeds = signal<StoredSpeeds>(readStoredSpeeds());

  /**
   * The speed playback should open at: what this device last picked for this network, or the
   * horizon-derived default.
   *
   * Reads the signal, so a `computed` over this recomputes when {@link setSpeed} lands - which is
   * what lets a mid-play speed change take effect on the next frame.
   */
  speedFor(networkId: Id, horizonPeriods: number): number {
    const remembered = this.speeds()[String(networkId)];
    return isLadderSpeed(remembered) ? remembered : defaultSpeedFor(horizonPeriods);
  }

  /** True when this network carries an explicit pick rather than falling back to the default. */
  hasPick(networkId: Id): boolean {
    return isLadderSpeed(this.speeds()[String(networkId)]);
  }

  /**
   * Record a pick. Applies immediately and is saved on change - there is no apply step, because
   * this row is not part of the time base's all-three-together contract.
   *
   * A speed off the ladder is ignored rather than clamped: it can only come from a tampered key or
   * a control this build does not have, and neither is evidence of what the viewer wanted.
   */
  setSpeed(networkId: Id, speed: number): void {
    if (!isLadderSpeed(speed)) {
      return;
    }
    this.commit({ ...untracked(this.speeds), [String(networkId)]: speed });
  }

  /** Drop this network's pick, so it falls back to the horizon-derived default again. */
  forget(networkId: Id): void {
    const next = { ...untracked(this.speeds) };
    delete next[String(networkId)];
    this.commit(next);
  }

  /**
   * The one write path: the signal first, storage second.
   *
   * That order is the degrade rule made structural - a rejected write leaves the session's state
   * correct and only costs the pick its persistence. `untracked` above keeps a caller that happens
   * to sit in a reactive context from taking a dependency on the map it is about to replace.
   */
  private commit(next: StoredSpeeds): void {
    this.speeds.set(next);
    writeStoredSpeeds(next);
  }
}

/**
 * Reads the key, keeping only what is currently a ladder speed.
 *
 * Everything about the parse is defensive because none of it is under this build's control: the key
 * can be absent, hand-edited, truncated, or written by a build whose ladder had other members.
 */
function readStoredSpeeds(): StoredSpeeds {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return {};
    }
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return {};
    }
    const clean: Record<string, number> = {};
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      if (isLadderSpeed(value)) {
        clean[key] = value;
      }
    }
    return clean;
  } catch {
    // Unreadable or unavailable storage is a preference not held, never an error: playback still
    // opens on the horizon-derived default.
    return {};
  }
}

function writeStoredSpeeds(speeds: StoredSpeeds): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(speeds));
  } catch {
    // Storage can be denied (private mode, disabled cookies) or full. The in-memory signal above is
    // already updated, so the setting works for this session and simply does not survive a reload.
  }
}
