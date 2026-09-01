import { Id } from '../../core/models';
import { networkNameFromFileName } from './file-names';

/**
 * The roles a batch assigns, and the order it imports in, for several workbooks in one
 * pass (FR-28).
 *
 * Pure - no Angular, no HTTP - like `file-names.ts` beside it and for the same reason. What is here
 * is the whole of what FR-28 decides *before* a request is made: which file is the baseline, what
 * each of the others is, in what order they go, which one carries the project's baseline flag, and
 * what happens to a variant whose base never got created. All of it is a function of the files and
 * two facts about the project, so all of it is testable without a server.
 *
 * ## Two names that must not become one
 *
 * These two have to stay apart, because conflating them is how the wrong thing gets marked:
 *
 * - **The project's baseline flag** - `NETWORK.is_baseline`, at most one per project. It
 *   is what the comparison view measures the others against. `POST /networks/import` takes it as
 *   `baseline`, and a second one is a 409 `BASELINE_ALREADY_SET`.
 * - **The base of this batch's variant edges** - the network the other files are recorded as
 *   `CONFIGURATION_VARIANT`s *of*. `POST /networks/import` takes it as `baseNetworkId`, and it is
 *   what draws the batch as a fork forest in the provenance tree.
 *
 * The roles step asks **one** question - "which of these files is the baseline?" - and that answer
 * feeds both, but only when the project has no baseline already. When it has one,
 * {@link importPlan} leaves the flag exactly where it is and uses the chosen file as the edge base
 * alone. The step has to *say* that rather than leave it to be discovered at the first request,
 * which is what {@link baselineFlagNote} is for: a batch of eight whose first file comes back 409
 * has not merely failed, it has taken all six of its variants down with it, since a variant edge
 * needs its base's id.
 *
 * ## Why the baseline goes first, and what that costs the files behind it
 *
 * `baseNetworkId` names a network that has to exist, so the baseline is imported before anything
 * that points at it. The consequence is the one case a per-file report has to be able to
 * express and a single status code cannot: a baseline that is **refused** leaves every file marked
 * *variant of the baseline* with nothing to point at. Those files are **not attempted** - see
 * {@link attemptable} - rather than quietly imported as independent networks, because a network that
 * arrived as a root is indistinguishable from a file the wizard was told was independent, which is
 * the same argument the backend makes for refusing a cross-project `baseNetworkId` outright rather
 * than dropping the edge. Files marked *independent* are unaffected and still import: they were
 * never waiting on anything.
 *
 * ## What is not decided here
 *
 * Nothing looks at the project's existing network *names*. A name already used in the
 * project takes the next version number exactly as any other network of that name would - that is
 * the server's, and pre-empting it client-side would turn the documented way to add a variant into
 * an error. And nothing inspects a file's contents: one mapping applies to the whole batch, and a
 * file whose headers do not match reports it as its own row-level errors.
 */

/** What one file of the batch is. */
export const BatchRole = {
  /** The one file the others are measured against. Exactly one per batch, imported first. */
  BASELINE: 'BASELINE',
  /** A `CONFIGURATION_VARIANT` of the baseline - the default, because a folder is usually one study. */
  VARIANT: 'VARIANT',
  /** Its own network, with no edge. */
  INDEPENDENT: 'INDEPENDENT',
} as const;

export type BatchRole = (typeof BatchRole)[keyof typeof BatchRole];

/** What the project's baseline flag is doing, as far as the wizard could find out. */
export const ProjectBaseline = {
  /** No network of this project carries the flag, so the chosen file may take it. */
  NONE: 'NONE',
  /** One already does. The flag stays where it is; the chosen file is only the edge base. */
  PRESENT: 'PRESENT',
  /** The project's networks could not be read. The flag is left alone - see {@link baselineFlagNote}. */
  UNKNOWN: 'UNKNOWN',
} as const;

export type ProjectBaseline = (typeof ProjectBaseline)[keyof typeof ProjectBaseline];

/** Which file the user picked as the baseline, and which of the rest they marked independent. */
export interface RoleAssignment {
  readonly baselineIndex: number;
  /** Indices marked *independent network*. Everything else defaults to *variant*. */
  readonly independent: ReadonlySet<number>;
}

/** The default the roles step opens with: the first file as baseline, every other one a variant. */
export const DEFAULT_ROLES: RoleAssignment = { baselineIndex: 0, independent: new Set<number>() };

/** One file of the batch, as the roles step lists it. */
export interface BatchRow {
  readonly file: File;
  readonly fileName: string;
  /** The network name taken from the file name, or null when nothing survives stripping it. */
  readonly name: string | null;
  readonly role: BatchRole;
}

/** One `POST /networks/import`, in the order it will be made. */
export interface BatchStep {
  readonly file: File;
  readonly name: string;
  readonly role: BatchRole;
  /** Send `baseline=true`: the baseline file, and only where the project has no flag set. */
  readonly setsBaselineFlag: boolean;
  /** Send `baseNetworkId`, once the baseline has produced one. */
  readonly needsBase: boolean;
}

/** Everything the batch will do, decided before the first request. */
export interface BatchPlan {
  /** The requests, baseline first. */
  readonly steps: readonly BatchStep[];
  /** Files that cannot be sent at all: nothing is left of the name once the extension is stripped. */
  readonly unnamed: readonly BatchRow[];
  readonly baselineName: string | null;
  /** True when this batch will set the project's baseline flag - see the module note. */
  readonly setsBaselineFlag: boolean;
  readonly variants: number;
  readonly independents: number;
}

/**
 * Refused before anything was sent, because the file has no name to give its network.
 *
 * Stated as an outcome rather than as a blocked *upload*: the file refuses itself, and
 * the rest of the batch import exactly as they would have.
 */
export const UNNAMED_REASON =
  'Refused before it was sent. Nothing is left of this file’s name once the extension is stripped, ' +
  'and a network is named after its file (FR-28) - the wizard does not invent one. Rename ' +
  'the file and import it on its own.';

/** Why a variant of a baseline that was never created is not attempted rather than imported bare. */
export const NOT_ATTEMPTED_REASON =
  'Not attempted. This file was marked a variant of the baseline, and the baseline was not created - ' +
  'a configuration-variant edge needs its base network’s id. Nothing was sent and ' +
  'nothing exists for it. Fix the baseline’s file and import this one again, or import it as an ' +
  'independent network.';

/**
 * The rows the roles step lists: one per file, in the order they were chosen.
 *
 * The baseline index is *resolved* rather than trusted - a file whose name is empty can never be the
 * baseline, so an assignment pointing at one (or off the end of a list the user has since changed)
 * falls back to the first file that can be named. That keeps "exactly one baseline" true of the rows
 * themselves rather than of a rule the component has to remember to apply, and it is why the
 * template can read `role === BASELINE` for its radio instead of comparing indices.
 */
export function batchRows(
  files: readonly File[],
  assignment: RoleAssignment,
): readonly BatchRow[] {
  const names = files.map((file) => networkNameFromFileName(file.name));
  const baseline = resolveBaselineIndex(names, assignment.baselineIndex);
  return files.map((file, index) => ({
    file,
    fileName: file.name,
    name: names[index],
    role: roleOf(index, baseline, names[index], assignment.independent),
  }));
}

/**
 * The first file that can be named, or the requested one when it can be.
 *
 * Exported because the roles step disables the baseline radio on an unnamed row, and a rule the
 * template applies and this module also applies is a rule with two implementations.
 */
export function resolveBaselineIndex(
  names: readonly (string | null)[],
  requested: number,
): number {
  const inRange =
    Number.isInteger(requested) && requested >= 0 && requested < names.length;
  if (inRange && names[requested] !== null) {
    return requested;
  }
  return names.findIndex((name) => name !== null);
}

/**
 * What the batch will do, in the order it will do it (FR-28).
 *
 * @param rows            the roles step's rows, in file order
 * @param projectBaseline what the project's baseline flag is already doing
 */
export function importPlan(
  rows: readonly BatchRow[],
  projectBaseline: ProjectBaseline,
): BatchPlan {
  const named = rows.filter((row) => row.name !== null);
  const baseline = named.find((row) => row.role === BatchRole.BASELINE) ?? null;
  // The baseline first, because a variant edge cannot name a base that does not exist yet;
  // everything else keeps the order the files were chosen in, so the report reads down
  // the same list the roles step showed.
  const ordered = baseline ? [baseline, ...named.filter((row) => row !== baseline)] : named;

  // Only where the project has none. UNKNOWN is treated as PRESENT deliberately: the cost of not
  // setting a flag is a checkbox the researcher sets later (FR-29), and the cost of setting one
  // wrongly is a 409 on the *baseline* file, which takes every variant behind it down with it.
  const setsBaselineFlag = baseline !== null && projectBaseline === ProjectBaseline.NONE;

  return {
    steps: ordered.map((row) => ({
      file: row.file,
      name: row.name as string,
      role: row.role,
      setsBaselineFlag: setsBaselineFlag && row.role === BatchRole.BASELINE,
      needsBase: row.role === BatchRole.VARIANT,
    })),
    unnamed: rows.filter((row) => row.name === null),
    baselineName: baseline?.name ?? null,
    setsBaselineFlag,
    variants: named.filter((row) => row.role === BatchRole.VARIANT).length,
    independents: named.filter((row) => row.role === BatchRole.INDEPENDENT).length,
  };
}

/**
 * Whether a step can be sent at all, given what the baseline did.
 *
 * The whole of "the batch stops at nothing": a refusal answers for its own file, the sequence keeps
 * going, and the *only* thing one file's failure can do to another is leave a variant with no base.
 */
export function attemptable(step: BatchStep, baseNetworkId: Id | null): boolean {
  return !step.needsBase || baseNetworkId !== null;
}

/** What the roles step says about the project's baseline flag before anything is imported. */
export interface BaselineFlagNote {
  readonly tone: 'info' | 'warning';
  readonly message: string;
}

/**
 * The sentence the roles step has to carry - it says exactly that rather than
 * silently failing at the fifth file.
 *
 * @param existingBaselineName the network already carrying the flag, when there is one
 */
export function baselineFlagNote(
  projectBaseline: ProjectBaseline,
  existingBaselineName: string | null,
): BaselineFlagNote {
  if (projectBaseline === ProjectBaseline.NONE) {
    return {
      tone: 'info',
      message:
        'This project has no baseline yet, so the file you choose below will be marked as the ' +
        'project’s baseline as well as being the base of this batch’s variant edges.',
    };
  }
  if (projectBaseline === ProjectBaseline.PRESENT) {
    return {
      tone: 'info',
      message:
        `This project already has a baseline${existingBaselineName ? ` - ${existingBaselineName}` : ''}` +
        ', and it stays where it is. A project has at most one, so the file you choose ' +
        'below is used only as the base of this batch’s variant edges: every variant is recorded ' +
        'against it and the tree draws them under it, but the project’s baseline flag does not move.',
    };
  }
  return {
    tone: 'warning',
    message:
      'This project’s networks could not be read, so the wizard does not know whether it already ' +
      'has a baseline. The flag is therefore left alone and no file will be marked as it - the ' +
      'variant edges below are unaffected, and a baseline can be set from the project afterwards. ' +
      'Retry the read to have the choice.',
  };
}

// ------------------------------------------------------------------ the report at the top

/** How one file of the batch ended. */
export const BatchStatus = {
  /** Queued, or in flight. The report is written as the batch runs, not at the end. */
  PENDING: 'PENDING',
  CREATED: 'CREATED',
  /** The server answered and refused it: row errors, or a refusal of the request itself. */
  REFUSED: 'REFUSED',
  /** Nothing was sent - see {@link NOT_ATTEMPTED_REASON} and {@link UNNAMED_REASON}. */
  NOT_ATTEMPTED: 'NOT_ATTEMPTED',
} as const;

export type BatchStatus = (typeof BatchStatus)[keyof typeof BatchStatus];

export type BatchVerdict = 'RUNNING' | 'CREATED_ALL' | 'PARTIAL' | 'CREATED_NONE';

/** What the top of the report step claims about the batch as a whole. */
export interface BatchSummary {
  readonly verdict: BatchVerdict;
  readonly tone: 'info' | 'success' | 'warning' | 'danger';
  readonly title: string;
  readonly message: string;
  readonly total: number;
  readonly created: number;
  readonly refused: number;
  readonly notAttempted: number;
  readonly pending: number;
}

/**
 * The one sentence a reader takes away from a batch.
 *
 * > "A batch that created some networks and refused others says so at the top rather than reading as
 * > either a success or a failure."
 *
 * That is why `PARTIAL` is a verdict of its own and not a success with a footnote or a failure with
 * an exception. Rendering a seven-of-eight batch green would hide the file that has to be fixed;
 * rendering it red would suggest the seven have to be re-imported, which is precisely the lottery
 * a batch-wide transaction was rejected to avoid. The counts are in the summary because "which
 * of these four things happened, and to how many" is the question, and a reader should not have to
 * total a table to answer it.
 */
export function batchSummary(statuses: readonly BatchStatus[]): BatchSummary {
  const total = statuses.length;
  const count = (status: BatchStatus) => statuses.filter((entry) => entry === status).length;
  const created = count(BatchStatus.CREATED);
  const refused = count(BatchStatus.REFUSED);
  const notAttempted = count(BatchStatus.NOT_ATTEMPTED);
  const pending = count(BatchStatus.PENDING);
  const counts = { total, created, refused, notAttempted, pending };

  if (pending > 0) {
    return {
      ...counts,
      verdict: 'RUNNING',
      tone: 'info',
      title: `Importing - ${total - pending} of ${total} files done.`,
      message:
        'One request per file, in the order below, baseline first. Each file is its own ' +
        'transaction, so this list is already true of the files it has reached.',
    };
  }
  if (created === total && total > 0) {
    return {
      ...counts,
      verdict: 'CREATED_ALL',
      tone: 'success',
      title: `All ${total} files imported.`,
      message:
        `${total} networks were created, each named after its file. They are in the project’s ` +
        'network table and in the lineage beneath it. Rename any of them from that table (FR-29).',
    };
  }
  if (created === 0) {
    return {
      ...counts,
      verdict: 'CREATED_NONE',
      tone: 'danger',
      title: 'Nothing was imported.',
      message:
        `No network was created: ${describeFailures(refused, notAttempted)}. Nothing in the ` +
        'project changed. Fix the files below and import them again.',
    };
  }
  return {
    ...counts,
    verdict: 'PARTIAL',
    tone: 'warning',
    title: `Partly imported - ${created} of ${total} files created a network.`,
    message:
      `This is neither a success nor a failure. ${describeFailures(refused, notAttempted)}, and ` +
      'the rest were created and exist. Each file is its own transaction, so the ' +
      'networks marked *Created* below are in the project already - the remedy is to fix the named ' +
      'files and import those files again, not to import the batch again.',
  };
}

function describeFailures(refused: number, notAttempted: number): string {
  const parts: string[] = [];
  if (refused > 0) {
    parts.push(`${refused} ${refused === 1 ? 'file was' : 'files were'} refused`);
  }
  if (notAttempted > 0) {
    parts.push(`${notAttempted} ${notAttempted === 1 ? 'was' : 'were'} not attempted`);
  }
  return parts.join(' and ') || 'nothing was sent';
}

function roleOf(
  index: number,
  baselineIndex: number,
  name: string | null,
  independent: ReadonlySet<number>,
): BatchRole {
  if (index === baselineIndex && name !== null) {
    return BatchRole.BASELINE;
  }
  return independent.has(index) ? BatchRole.INDEPENDENT : BatchRole.VARIANT;
}
