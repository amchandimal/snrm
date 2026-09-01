import { Id } from './common.model';

/**
 * Project archive DTOs - the reproducibility surface.
 *
 * Two endpoints, deliberately asymmetric:
 *
 * - `GET /projects/{id}/archive` → a `.zip`. No JSON shape at all: `bundle.json` plus one
 *   XML document per network, which is why this file describes only the restore.
 * - `POST /projects/archive/import` (multipart) → **201** carrying {@link ArchiveReport}, with a
 *   `Location` header naming the created project. The report already carries `projectId`, so
 *   nothing here reads that header.
 *
 * Mirrors `ArchiveReport`, `ArchiveReport.Finding` and `ProjectBundle.Counts` on the backend.
 *
 * ## The status codes are not the import wizard's
 *
 * A network import answers 200 with the report even when the file was rejected, because
 * the report *is* the answer. A restore answers **201**, because it always created a project -
 * findings qualify a restore that happened rather than explaining one that did not. The only 4xx is
 * an archive that cannot be read at all: 422 `ARCHIVE_UNREADABLE`, which is an ordinary
 * `problem+json` and is surfaced through `core/problem-details`.
 */

/** A census of what a bundle claimed to hold, and of what the restore wrote. */
export interface ArchiveCounts {
  readonly networks: number;
  readonly products: number;
  readonly scenarios: number;
  readonly events: number;
  readonly runs: number;
  readonly metricResults: number;
  readonly timeseriesRows: number;
}

/**
 * Codes a finding can carry.
 *
 * Stable identifiers, so the client branches on the rule and never on the prose - the same contract
 * `ProblemCode` states for errors. The list mirrors the constants on the backend's `ArchiveReport`;
 * a code this build does not know is rendered rather than dropped (see `archive-report.ts`).
 */
export const ArchiveFindingCode = {
  /** The archived runs came from a simulation engine this installation does not run. */
  ENGINE_VERSION_MISMATCH: 'ENGINE_VERSION_MISMATCH',
  /**
   * The archive carried some of a project's networks rather than all of them (FR-24).
   *
   * Not a shortfall: the manifest counted what the subset holds, so `restoredCounts` matches
   * `sourceCounts` exactly. It is here because the restored project *looks* complete - a catalogue
   * with entries nothing uses, a scenario that refuses to run, a variant with no recorded parent -
   * and this is the only thing that makes those three legible as consequences of a selection rather
   * than as damage.
   */
  ARCHIVE_IS_SUBSET: 'ARCHIVE_IS_SUBSET',
  /** An event's target did not resolve; the event is restored broken rather than dropped. */
  EVENT_TARGET_UNRESOLVED: 'EVENT_TARGET_UNRESOLVED',
  /** A node- or link-scoped metric result named an element the restored project does not have. */
  METRIC_SCOPE_UNRESOLVED: 'METRIC_SCOPE_UNRESOLVED',
  /** A network could not keep its archived version number, so the restore set it back. */
  NETWORK_VERSION_ADJUSTED: 'NETWORK_VERSION_ADJUSTED',
  /** The archived project name was already this user's, so the restore was suffixed. */
  PROJECT_RENAMED: 'PROJECT_RENAMED',
} as const;

export type ArchiveFindingCode = (typeof ArchiveFindingCode)[keyof typeof ArchiveFindingCode];

/**
 * One thing about the restore worth knowing.
 *
 * Note what is *not* here: a severity. The backend orders the list most-consequential-first and
 * carries no severity field, so the reading is the client's - see `features/projects/archive-report.ts`,
 * which derives it from {@link code} and defaults an unrecognised code to the cautious answer.
 */
export interface ArchiveFinding {
  /** See {@link ArchiveFindingCode}; typed as `string` because a later build may add one. */
  readonly code: string;
  /** What it is about - a scenario name, a network key, an engine version. */
  readonly subject: string | null;
  /** The whole of it, in a sentence a researcher can act on. Shown verbatim. */
  readonly message: string;
}

/**
 * `POST /projects/archive/import`: what the restore did.
 *
 * A restore with findings still succeeded. `findings` is the answer, and
 * `restoredCounts` against `sourceCounts` is the arithmetic behind it - any shortfall is explained
 * by a finding.
 */
export interface ArchiveReport {
  /** The project the restore created. Never merged into an existing one. */
  readonly projectId: Id;
  /** Its name, suffixed when the owner already had one by that name. */
  readonly projectName: string;
  /** What the archive's manifest claimed to hold; null when the bundle carried no manifest. */
  readonly sourceCounts: ArchiveCounts | null;
  /** What was written. */
  readonly restoredCounts: ArchiveCounts;
  /** The engine that produced the archived runs, from the manifest; null when unstated. */
  readonly engineVersion: string | null;
  /**
   * Whether that engine is this installation's.
   *
   * False means every restored run's numbers came from a different model. They are readable and
   * citable; putting them beside a locally computed run compares two engines as much as two
   * networks. A warning, never a refusal - an archive's purpose is to be legible later,
   * and "later" is when the engine has moved on.
   */
  readonly engineMatches: boolean;
  /** Most consequential first, as the server ordered them; empty on a clean restore. */
  readonly findings: readonly ArchiveFinding[];
}
