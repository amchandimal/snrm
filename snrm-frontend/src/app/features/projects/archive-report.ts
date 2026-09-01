import { ArchiveCounts, ArchiveFinding, ArchiveFindingCode, ArchiveReport } from '../../core/models';

/**
 * Reading a restore report.
 *
 * Free of Angular and of HTTP, like `provenance-tree.ts` beside it and `core/run-discard.ts`: the
 * part that has to be right is what the screen *claims* about a set of restored results, and a
 * component spec cannot check a claim without rendering a card.
 *
 * ## The severity is this client's reading, not a field
 *
 * `ArchiveReport.Finding` carries `code`, `subject` and `message` and no severity - the backend
 * ranks the list instead, most consequential first, and puts `ENGINE_VERSION_MISMATCH` at the head
 * of it because it qualifies every number the restore just wrote. Rendering five findings as five
 * identical rows would flatten that ranking back out, so the severity is derived here from the
 * stable code, exactly as `problem-details.ts` branches on `problem.code` and never on the sentence.
 *
 * Three levels rather than the import wizard's two, because nothing in an archive report is an
 * error: the restore succeeded, or there would be no report to read (an unreadable archive is a 422
 * `ARCHIVE_UNREADABLE`). What varies is how much of the result a finding qualifies -
 * {@link ArchiveSeverity.CRITICAL} every number, {@link ArchiveSeverity.WARNING} one scenario or
 * one metric row, {@link ArchiveSeverity.NOTICE} nothing at all.
 *
 * **An unknown code is a warning.** A later backend adding a sixth code must not have it rendered
 * as harmless by a frontend that has never heard of it; the cautious default is the one that gets
 * read.
 *
 * ## Counts are compared, not just displayed
 *
 * "`restoredCounts` against `sourceCounts` - any shortfall is explained by a finding" is the
 * backend's own instruction to the reader (api-tests 76), so {@link countRows} does the subtraction
 * rather than printing two columns and leaving it to the eye. A row with no shortfall is still
 * shown: "7 of 7 runs" is the reassurance the comparison exists to give.
 *
 * Note that an unresolved event target produces **no** shortfall - the event is restored, carrying a
 * negative sentinel id, precisely so a scenario that refused to run still refuses to run. The
 * finding is the only sign of it, which is why findings and counts are rendered together.
 */

/** How much of the restored experiment a finding qualifies. See the module note. */
export const ArchiveSeverity = {
  /** Every restored number: read this before comparing anything. */
  CRITICAL: 'CRITICAL',
  /** Something specific is restored and broken, or restored and unattributable. */
  WARNING: 'WARNING',
  /** The restore did what it should; this explains a difference the reader would otherwise notice. */
  NOTICE: 'NOTICE',
} as const;

export type ArchiveSeverity = (typeof ArchiveSeverity)[keyof typeof ArchiveSeverity];

/** A finding with its severity resolved, ready to render. */
export interface ShapedFinding extends ArchiveFinding {
  readonly severity: ArchiveSeverity;
}

/** One line of the source-versus-restored comparison. */
export interface CountRow {
  readonly label: string;
  /** What the manifest claimed; null when the bundle carried no manifest. */
  readonly source: number | null;
  readonly restored: number;
  /** How many are missing. Zero when the counts agree or when there is nothing to compare against. */
  readonly shortfall: number;
}

/** Ranking used to group the findings; lower sorts first. */
const SEVERITY_RANK: Readonly<Record<ArchiveSeverity, number>> = {
  [ArchiveSeverity.CRITICAL]: 0,
  [ArchiveSeverity.WARNING]: 1,
  [ArchiveSeverity.NOTICE]: 2,
};

/**
 * The severity of one finding code.
 *
 * - `ENGINE_VERSION_MISMATCH` is critical: the results are readable but not comparable, which is a
 *   statement about the whole restore rather than about one row.
 * - `EVENT_TARGET_UNRESOLVED` is a warning: the scenario is restored and will be refused at
 *   submission until its target is repointed - broken in exactly the way it was broken before.
 * - `METRIC_SCOPE_UNRESOLVED` is a warning: the value survives, its attribution to an element does
 *   not, so a per-node reading of that metric is quietly incomplete.
 * - `NETWORK_VERSION_ADJUSTED` is a notice: the archived version number *was* restored; the finding
 *   explains why the sequence has a gap.
 * - `PROJECT_RENAMED` is a notice: nothing was lost, and the new name is on screen anyway.
 * - `ARCHIVE_IS_SUBSET` is a notice, and the judgement is worth stating (FR-24). Nothing went
 *   wrong: the file held some of a project's networks because somebody ticked some of them, and the
 *   counts agree with the manifest. What the finding explains is why the restored project looks
 *   the way it does - an unused catalogue entry, a scenario that will not run, a variant with no
 *   parent - which is exactly what {@link ArchiveSeverity.NOTICE} is for: "the restore did what it
 *   should; this explains a difference the reader would otherwise notice." Where the *consequences*
 *   are warnings in their own right, they arrive as their own findings -
 *   `EVENT_TARGET_UNRESOLVED` beside this one, once per affected event.
 */
export function archiveSeverity(code: string): ArchiveSeverity {
  switch (code) {
    case ArchiveFindingCode.ENGINE_VERSION_MISMATCH:
      return ArchiveSeverity.CRITICAL;
    case ArchiveFindingCode.EVENT_TARGET_UNRESOLVED:
    case ArchiveFindingCode.METRIC_SCOPE_UNRESOLVED:
      return ArchiveSeverity.WARNING;
    case ArchiveFindingCode.ARCHIVE_IS_SUBSET:
    case ArchiveFindingCode.NETWORK_VERSION_ADJUSTED:
    case ArchiveFindingCode.PROJECT_RENAMED:
      return ArchiveSeverity.NOTICE;
    default:
      // A code this build has never seen. See the module note: cautious, never harmless.
      return ArchiveSeverity.WARNING;
  }
}

/**
 * The findings, each carrying its severity, grouped by severity.
 *
 * The sort is **stable**, so within one severity the server's own ordering survives - it ranked the
 * list before sending it, and re-ordering inside a group would discard information this client does
 * not have.
 */
export function shapeFindings(findings: readonly ArchiveFinding[]): readonly ShapedFinding[] {
  return findings
    .map((finding) => ({ ...finding, severity: archiveSeverity(finding.code) }))
    .sort((a, b) => SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity]);
}

/** How many findings of one severity, for the summary badges. */
export function countBySeverity(
  findings: readonly ShapedFinding[],
  severity: ArchiveSeverity,
): number {
  return findings.filter((finding) => finding.severity === severity).length;
}

/**
 * What a mismatched engine version costs, in the backend's own words.
 *
 * Prefers the `ENGINE_VERSION_MISMATCH` finding's message verbatim: it is the sentence the server
 * wrote about the two specific versions involved, and paraphrasing it here would give the tool two
 * answers to one question - the rule the export filename follows for the same reason.
 *
 * The fallback exists because `engineMatches: false` with no matching finding is representable in
 * the DTO, and a warning banner with nothing in it would be worse than a generic sentence.
 * Returns null when the engine matches, which is the signal not to raise the banner at all.
 */
export function engineWarning(report: ArchiveReport): string | null {
  if (report.engineMatches) {
    return null;
  }
  const stated = report.findings.find(
    (finding) => finding.code === ArchiveFindingCode.ENGINE_VERSION_MISMATCH,
  );
  if (stated) {
    return stated.message;
  }
  const archived = report.engineVersion ?? '(unstated)';
  return (
    `The archived runs were produced by simulation engine ${archived}, which is not the engine ` +
    'this installation runs. The results are restored and readable, and each run records the ' +
    'engine that produced it - but a stored parameter set replays exactly only against the engine ' +
    'that wrote it. Re-run before comparing these results with anything computed here; ' +
    'a comparison across engine versions measures the engine as much as the network.'
  );
}

/** The count comparison, in the order a reader builds an experiment: structure, then results. */
export function countRows(report: ArchiveReport): readonly CountRow[] {
  const source = report.sourceCounts;
  const restored = report.restoredCounts;
  const row = (label: string, key: keyof ArchiveCounts): CountRow => {
    const claimed = source ? source[key] : null;
    return {
      label,
      source: claimed,
      restored: restored[key],
      shortfall: claimed === null ? 0 : Math.max(0, claimed - restored[key]),
    };
  };
  return [
    row('Networks', 'networks'),
    row('Products', 'products'),
    row('Disruption scenarios', 'scenarios'),
    row('Disruption events', 'events'),
    row('Simulation runs', 'runs'),
    row('Metric results', 'metricResults'),
    row('Time-series rows', 'timeseriesRows'),
  ];
}

/** True when anything the manifest claimed did not arrive - the reason to read the findings. */
export function hasShortfall(report: ArchiveReport): boolean {
  return countRows(report).some((row) => row.shortfall > 0);
}

/**
 * One sentence for the top of the report card.
 *
 * States what was created and what the restore is worth in the same breath, because those are the
 * two things a researcher decides on: a clean restore is citable as-is, one with an engine mismatch
 * is readable but not comparable, and one with a shortfall needs the findings read before either.
 */
export function restoreHeadline(report: ArchiveReport): string {
  const created = `Restored into a new project: “${report.projectName}” (#${report.projectId}).`;
  if (!report.engineMatches) {
    return (
      `${created} The archived results were computed by a different simulation engine - read the ` +
      'warning below before comparing them with anything computed here.'
    );
  }
  if (hasShortfall(report)) {
    return `${created} Some of what the archive claimed to hold did not arrive; the findings say why.`;
  }
  if (report.findings.length) {
    return `${created} It reproduced the archived experiment, with notes worth reading below.`;
  }
  return `${created} It reproduced the archived experiment exactly - no findings.`;
}

/**
 * What the restore counts as, in the run provenance sense (FR-20).
 *
 * Every run this created carries `importedAt`, which is what distinguishes a number this
 * installation computed from one it was handed - and what the discard confirmation warns about
 * separately (`core/run-discard.ts`). Returned as a sentence so the report can say it once, where
 * the runs are being announced, rather than leaving it to be discovered at a delete dialog.
 */
export function provenanceNote(report: ArchiveReport): string | null {
  const runs = report.restoredCounts.runs;
  if (runs === 0) {
    return null;
  }
  return (
    `${runs === 1 ? 'The restored run is marked' : `All ${runs} restored runs are marked`} as ` +
    'imported: each carries the time it was restored and the run id it held where it was computed, ' +
    'and each freezes its network exactly as a locally computed run does.'
  );
}
