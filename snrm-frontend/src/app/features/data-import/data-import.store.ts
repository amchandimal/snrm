import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiService } from '../../core/api.service';
import {
  Duration,
  Id,
  ImportMapping,
  ImportPreview,
  ImportReport,
  ImportSheet,
  ImportTimeBase,
  Network,
  RoundingPolicy,
} from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import {
  BatchPlan,
  BatchRole,
  BatchRow,
  BatchStatus,
  BatchStep,
  DEFAULT_ROLES,
  NOT_ATTEMPTED_REASON,
  ProjectBaseline,
  RoleAssignment,
  UNNAMED_REASON,
  attemptable,
  baselineFlagNote,
  batchRows,
  batchSummary as summariseBatch,
  importPlan,
} from './batch-plan';
// Aliased where a signal below carries the natural name: the store's surface is what the templates
// read, so the function gives way rather than the property.
import {
  UploadShape,
  isWorkbook,
  networkNameFromFileName,
  uploadRefusal as refusalFor,
  uploadShape,
} from './file-names';

/** Which step of the wizard is on screen. */
export const ImportStep = {
  UPLOAD: 'UPLOAD',
  MAPPING: 'MAPPING',
  /** Batch only: which file is the baseline, and what each of the others is (FR-28). */
  ROLES: 'ROLES',
  REPORT: 'REPORT',
} as const;

export type ImportStep = (typeof ImportStep)[keyof typeof ImportStep];

/** One network: the three steps the wizard has always had, unchanged by FR-28. */
export const IMPORT_STEPS: readonly ImportStep[] = [
  ImportStep.UPLOAD,
  ImportStep.MAPPING,
  ImportStep.REPORT,
];

/** Several workbooks: the roles step sits between mapping and the report (FR-28). */
export const BATCH_IMPORT_STEPS: readonly ImportStep[] = [
  ImportStep.UPLOAD,
  ImportStep.MAPPING,
  ImportStep.ROLES,
  ImportStep.REPORT,
];

/** The stepper's sequence. Four steps for a batch, the original three for one network. */
export function importSteps(batch: boolean): readonly ImportStep[] {
  return batch ? BATCH_IMPORT_STEPS : IMPORT_STEPS;
}

/** What happened to one file of a batch (FR-28). */
export interface BatchOutcome {
  readonly fileName: string;
  /** The network name taken from the file name; null only for a file that could not be named. */
  readonly name: string | null;
  readonly role: BatchRole;
  readonly status: BatchStatus;
  /** The two-stage report for this file, when the server produced one. */
  readonly report: ImportReport | null;
  /** The created network - its name and the version the server assigned. */
  readonly network: Network | null;
  /** A sentence for the cases a report cannot carry: not attempted, unnamed, a refused request. */
  readonly message: string | null;
}

/**
 * State for the import wizard - three steps for one network, four for a batch
 * (FR-02, FR-28).
 *
 * > "three steps: upload CSV files or an Excel workbook → map columns to the canonical schema →
 * > review the validation report and confirm. The imported network opens in the editor."
 *
 * > "When the upload holds more than one `.xlsx`, the wizard gains a **roles step** between mapping
 * > and the report… The report step then becomes a **per-file report**."
 *
 * ## Why the files live here
 *
 * The wizard posts them **twice**: once to `/networks/import/preview` to learn the headers, and again
 * to `/networks/import` with the confirmed mapping. Keeping them client-side between the two is what
 * makes the import stateless server-side - no temporary upload to expire, garbage-collect or authorise
 * a second time, and no possibility of the confirmed import running against a different file than the
 * one the user was looking at. It is also why the store is `providedIn: 'root'` but reset explicitly:
 * a `File` is not serialisable and must not outlive the wizard.
 *
 * ## Why the step is derived, not stored
 *
 * The wizard cannot be on the mapping step without a preview, or on the report step without a report.
 * Storing a step index alongside them means two sources of truth for one question and an "impossible"
 * state to guard against; deriving it from what has been fetched makes those states unrepresentable.
 * Going back a step therefore means dropping what came after it - {@link backToUpload} and
 * {@link backToMapping} - which is honest: the report was about a mapping the user is now changing.
 *
 * **FR-28's roles step extends that discipline rather than breaking it.** It is derived from
 * {@link roles} - the assignment itself, which is the thing the step is *about*, exactly as the
 * preview is what the mapping step is about - **and** from {@link batch}, which is a function of the
 * files. Two consequences, both structural rather than remembered: a roles step with no roles to
 * assign is unrepresentable, and so is one over an upload that is not a batch. A stored step index
 * would have made "on the roles step with one workbook" a state to guard against in three places.
 *
 * ## Why a batch commits from the roles step and has no dry run
 *
 * A single import is all-or-nothing, so `validateOnly=true` exists to let the user confirm a decision
 * they have been shown. A batch is not: each file is its own transaction, "and a file that
 * fails leaves the others imported and itself named" (FR-28). There is therefore nothing for a batch
 * dry run to be a rehearsal *of* - a refused file creates nothing either way, and the variants cannot
 * even be rehearsed, since `baseNetworkId` names a network a dry run does not create. So the roles
 * step's button is the import, and the report step is what happened, per file, as it happens.
 *
 * ## Why the dry run is a separate call from the commit
 *
 * `validateOnly=true` produces the report step 3 shows; the same request with it false creates the
 * network. Imports are transactional, so the user is confirming a decision, and confirming
 * something they have not been shown is exactly what the dry run exists to prevent. The two calls
 * validate identically, so a report that showed no errors and a commit that then fails can only mean
 * the data changed underneath - which is why the commit's report replaces the dry run's rather than
 * being assumed to match it.
 */
@Injectable({ providedIn: 'root' })
export class DataImportStore {
  private readonly api = inject(ApiService);

  // ------------------------------------------------------------------ raw state

  private readonly _projectId = signal<Id | null>(null);
  private readonly _files = signal<readonly File[]>([]);
  private readonly _preview = signal<ImportPreview | null>(null);
  private readonly _report = signal<ImportReport | null>(null);
  private readonly _mapping = signal<ImportMapping>({});
  private readonly _name = signal('');
  private readonly _baseline = signal(false);

  /** The time base the user confirmed in step 2, or null to let `network_meta` decide. */
  private readonly _timeBase = signal<ImportTimeBase | null>(null);

  /** FR-28: the roles the user assigned, and null until the roles step is opened. */
  private readonly _roles = signal<RoleAssignment | null>(null);
  /** FR-28: one entry per file, rewritten as each answer arrives. Null until the batch starts. */
  private readonly _batch = signal<readonly BatchOutcome[] | null>(null);
  /** FR-28: what the project's baseline flag is already doing - see {@link probeBaseline}. */
  private readonly _projectBaseline = signal<ProjectBaseline>(ProjectBaseline.UNKNOWN);
  private readonly _existingBaseline = signal<Network | null>(null);
  private readonly _probing = signal(false);

  private readonly _busy = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly projectId = this._projectId.asReadonly();
  readonly files = this._files.asReadonly();
  readonly preview = this._preview.asReadonly();
  readonly report = this._report.asReadonly();
  readonly mapping = this._mapping.asReadonly();
  readonly name = this._name.asReadonly();
  readonly baseline = this._baseline.asReadonly();
  readonly timeBase = this._timeBase.asReadonly();
  readonly roles = this._roles.asReadonly();
  readonly batchOutcomes = this._batch.asReadonly();
  readonly projectBaseline = this._projectBaseline.asReadonly();
  readonly probing = this._probing.asReadonly();
  readonly busy = this._busy.asReadonly();
  readonly error = this._error.asReadonly();

  // -------------------------------------------------------------------- derived

  /** How the wizard reads this upload: one network, several workbooks, or neither (FR-28). */
  readonly shape = computed(() => uploadShape(this._files().map((file) => file.name)));

  /** True when the upload is several workbooks - one network per file (FR-28). */
  readonly batch = computed(() => this.shape() === UploadShape.BATCH);

  /** Why this upload cannot be read at all, or null. Refuses *before* anything is posted. */
  readonly uploadRefusal = computed(() => refusalFor(this._files().map((file) => file.name)));

  /**
   * The names the batch would give, shown on the upload step before anything is committed.
   *
   * Deliberately not {@link rows}: no role has been chosen yet at that point, and a table showing
   * every file as *variant* before the question has been asked would answer it for the reader.
   */
  readonly derivedNames = computed(() =>
    this._files().map((file) => ({
      fileName: file.name,
      name: networkNameFromFileName(file.name),
    })),
  );

  /**
   * The files the preview is built from.
   *
   * **One file for a batch, and that is a deliberate rule rather than an optimisation**: files
   * imported together are files of one shape, which is why they are being imported together, so
   * asking for a mapping per file would charge for a case that does not arise. Posting all of them
   * would also be wrong on its own terms - the preview endpoint reads one network's worth of sheets,
   * so four workbooks would arrive as four `nodes` sheets and one `DUPLICATE_SHEET`.
   */
  readonly previewFiles = computed<readonly File[]>(() =>
    this.batch() ? this._files().slice(0, 1) : this._files(),
  );

  /** One row per file of the batch, with its derived name and its role. */
  readonly rows = computed<readonly BatchRow[]>(() =>
    this.batch() ? batchRows(this._files(), this._roles() ?? DEFAULT_ROLES) : [],
  );

  /** What the batch will do, in the order it will do it. Null when this is not a batch. */
  readonly plan = computed<BatchPlan | null>(() =>
    this.batch() ? importPlan(this.rows(), this._projectBaseline()) : null,
  );

  /** What the roles step says about the project's baseline flag. */
  readonly baselineNote = computed(() =>
    baselineFlagNote(this._projectBaseline(), this.describeExistingBaseline()),
  );

  /** The verdict at the top of a batch report - partial, complete, or nothing. */
  readonly batchSummary = computed(() => {
    const outcomes = this._batch();
    return outcomes ? summariseBatch(outcomes.map((outcome) => outcome.status)) : null;
  });

  /** Networks this batch created, in the order they were created. */
  readonly batchCreated = computed(() =>
    (this._batch() ?? []).filter((outcome) => outcome.status === BatchStatus.CREATED),
  );

  /**
   * The step on screen. Derived - see the class note.
   *
   * The roles step is guarded by `batch()` as well as by the assignment, so an upload that stops
   * being a batch cannot leave the wizard on a step about a batch.
   */
  readonly step = computed<ImportStep>(() => {
    if (this._report() || this._batch()) {
      return ImportStep.REPORT;
    }
    if (!this._preview()) {
      return ImportStep.UPLOAD;
    }
    return this.batch() && this._roles() ? ImportStep.ROLES : ImportStep.MAPPING;
  });

  /** The stepper's sequence for the upload on screen (FR-28). */
  readonly steps = computed(() => importSteps(this.batch()));

  readonly canPreview = computed(
    () => this._files().length > 0 && this.uploadRefusal() === null && !this._busy(),
  );

  /** Step 2 → 3 for a batch: no request, so nothing to wait for but the mapping being settled. */
  readonly canOpenRoles = computed(() => this.batch() && !!this._preview() && !this._busy());

  /** Step 3 → 4 for a batch: at least one file the wizard can name and therefore send. */
  readonly canImportBatch = computed(
    () => !this._busy() && (this.plan()?.steps.length ?? 0) > 0,
  );

  /**
   * Whether step 2 is complete enough to validate.
   *
   * Only a required column with no source blocks it: everything else the mapping step can get wrong is
   * something validation will report against a row, and finding that out from the report is the normal
   * path. A missing required column is different - it makes the whole sheet unreadable, so there is
   * nothing for a report to say beyond what is already on screen.
   */
  readonly unmappedRequired = computed(() => {
    const preview = this._preview();
    if (!preview) {
      return [] as readonly { sheet: ImportSheet; column: string }[];
    }
    const missing: { sheet: ImportSheet; column: string }[] = [];
    for (const sheet of preview.sheets) {
      for (const column of this.missingRequiredOf(sheet.sheet)) {
        missing.push({ sheet: sheet.sheet, column });
      }
    }
    return missing as readonly { sheet: ImportSheet; column: string }[];
  });

  readonly canValidate = computed(
    () => !!this._preview() && this._name().trim().length > 0 && !this._busy(),
  );

  /** Confirm is offered only when the dry run found no errors - any error refuses the import. */
  readonly canConfirm = computed(() => {
    const report = this._report();
    return !!report && report.valid && !report.committed && !this._busy();
  });

  readonly committedNetworkId = computed(() => {
    const report = this._report();
    return report?.committed ? (report.networkId ?? null) : null;
  });

  // --------------------------------------------------------------------- setup

  /** Called by the wizard on entry. Resets everything: a wizard always starts at step 1. */
  start(projectId: Id): void {
    this.reset();
    this._projectId.set(projectId);
  }

  reset(): void {
    this._projectId.set(null);
    this._files.set([]);
    this._preview.set(null);
    this._report.set(null);
    this._mapping.set({});
    this._name.set('');
    this._baseline.set(false);
    this._timeBase.set(null);
    this._roles.set(null);
    this._batch.set(null);
    this._projectBaseline.set(ProjectBaseline.UNKNOWN);
    this._existingBaseline.set(null);
    this._probing.set(false);
    this._error.set(null);
    this._busy.set(false);
  }

  dismissError(): void {
    this._error.set(null);
  }

  setName(name: string): void {
    this._name.set(name);
  }

  setBaseline(baseline: boolean): void {
    this._baseline.set(baseline);
  }

  // ------------------------------------------------------------ step 3, batch only (FR-28)

  /** Which file is the baseline. One across the batch - see `batch-plan.ts` on the two meanings. */
  setBaselineFile(index: number): void {
    this._roles.update((roles) => ({
      baselineIndex: index,
      // A file promoted to baseline stops being independent: the baseline is the base of the edges,
      // so "independent of itself" is not a state the step can express.
      independent: without(roles?.independent ?? DEFAULT_ROLES.independent, index),
    }));
  }

  /** Whether a non-baseline file is a variant of the baseline or its own network. */
  setRole(index: number, role: BatchRole): void {
    if (role === BatchRole.BASELINE) {
      this.setBaselineFile(index);
      return;
    }
    this._roles.update((roles) => {
      const independent = new Set(roles?.independent ?? DEFAULT_ROLES.independent);
      if (role === BatchRole.INDEPENDENT) {
        independent.add(index);
      } else {
        independent.delete(index);
      }
      return { baselineIndex: roles?.baselineIndex ?? DEFAULT_ROLES.baselineIndex, independent };
    });
  }

  /**
   * Step 2 → 3 of a batch: open the roles step and find out what the project's baseline flag is
   * already doing (FR-28).
   *
   * Setting `_roles` is what makes the step derivable - see the class note. The probe runs alongside
   * rather than before it: the step is about the roles, and the flag question qualifies one sentence
   * on it rather than gating the whole screen.
   */
  openRoles(): void {
    if (!this.batch() || !this._preview()) {
      return;
    }
    this._roles.update((roles) => roles ?? DEFAULT_ROLES);
    void this.probeBaseline();
  }

  /**
   * `GET /projects/{id}/networks`, read for one boolean: does this project already have a baseline?
   *
   * The roles step has to say so rather than silently failing at the fifth file - the
   * project's baseline flag is at most one per project, so a batch that asks for a second
   * one is refused on its *baseline* file, which takes every variant behind it down with it.
   *
   * **Read here rather than through `NetworksStore`**, following `SideBySideStore`'s rule for the
   * same situation: that store owns the dashboard's selection and its `deleteMany`, and a wizard
   * should not have either one injection away. Only two things are kept from the answer, and neither
   * is the list.
   *
   * **A failure degrades to `UNKNOWN` and no flag is set**, which is the asymmetry that matters:
   * not setting a flag costs a checkbox the researcher sets later (FR-29), and setting one wrongly
   * costs the batch. `provenance.store.ts` makes the same call for its own sweep - a wrong-looking
   * answer that says so beats an aborted screen.
   */
  async probeBaseline(): Promise<void> {
    const projectId = this._projectId();
    if (projectId === null || this._probing()) {
      return;
    }
    this._probing.set(true);
    try {
      const networks = await firstValueFrom(
        this.api.get<Network[]>(`/projects/${projectId}/networks`),
      );
      const existing = networks.find((network) => network.baseline) ?? null;
      this._existingBaseline.set(existing);
      this._projectBaseline.set(existing ? ProjectBaseline.PRESENT : ProjectBaseline.NONE);
    } catch {
      // Deliberately not `_error`: that banner means the wizard could not do what it was asked, and
      // this read is an aside the roles step states for itself.
      this._existingBaseline.set(null);
      this._projectBaseline.set(ProjectBaseline.UNKNOWN);
    } finally {
      this._probing.set(false);
    }
  }

  // ------------------------------------------------------------------ step 1–2

  addFiles(files: readonly File[]): void {
    // Same name and size means the same file re-dropped, which is a mistake rather than an intent:
    // two `nodes` sheets in one upload is a DUPLICATE_SHEET error server-side.
    this._files.update((existing) => {
      const merged = [...existing];
      for (const file of files) {
        if (!merged.some((seen) => seen.name === file.name && seen.size === file.size)) {
          merged.push(file);
        }
      }
      return merged;
    });
  }

  removeFile(file: File): void {
    this._files.update((existing) => existing.filter((seen) => seen !== file));
  }

  /** Step 1 → 2: `POST /networks/import/preview`, of one file when this is a batch. */
  async loadPreview(): Promise<void> {
    if (!this._files().length || this.uploadRefusal() !== null) {
      return;
    }
    this._busy.set(true);
    this._error.set(null);
    try {
      const preview = await firstValueFrom(
        this.api.post<ImportPreview>(
          '/networks/import/preview',
          this.formData(this.previewFiles()),
        ),
      );
      this._preview.set(preview);
      this._report.set(null);
      // The wizard confirms the clock rather than silently accepting it, but it should not make the
      // user retype what the file already said - so the form is seeded from the preview either way.
      // What `declared` changes is whether the UI insists on the confirmation.
      this._timeBase.set(preview.timeBase);
      if (!this._name().trim()) {
        // The file's own name first: an XML export carries it, so re-importing one needs nothing
        // typed. The filename is the fallback guess.
        this._name.set(preview.declaredName?.trim() || suggestName(this._files()));
      }
    } catch (failure) {
      this._error.set(problemMessage(failure, 'Could not read the uploaded files.'));
    } finally {
      this._busy.set(false);
    }
  }

  /** Sets, or clears with null, which canonical column a source column feeds. */
  mapColumn(sheet: ImportSheet, sourceColumn: string, canonicalColumn: string | null): void {
    this._mapping.update((mapping) => {
      const next: ImportMapping = { ...mapping };
      next[sheet] = { ...(mapping[sheet] ?? {}), [sourceColumn]: canonicalColumn };
      return next;
    });
  }

  /**
   * The server's auto-match as a lookup, rebuilt only when the preview changes.
   *
   * The mapping step asks {@link mappedColumn} once per *option* rather than once per dropdown - the
   * selection is carried by `[selected]` on the options, because a `[value]` binding on a `<select>` is
   * applied before `@for` has created any option and is silently dropped. That turns a linear scan into
   * a few hundred calls per change detection, so it is worth not scanning.
   */
  private readonly suggestions = computed(() => {
    const bySheet = new Map<ImportSheet, Map<string, string | null>>();
    for (const sheet of this._preview()?.sheets ?? []) {
      const columns = new Map<string, string | null>();
      for (const column of sheet.columns) {
        columns.set(column.sourceColumn, column.canonicalColumn);
      }
      bySheet.set(sheet.sheet, columns);
    }
    return bySheet;
  });

  /**
   * The canonical column a source column currently feeds: the user's override if there is one, and
   * otherwise the server's auto-match.
   *
   * A header that already matches the schema therefore arrives pre-selected, and the mapping
   * step is a confirmation rather than a chore - which is the whole reason the server sends a suggestion
   * with the preview. An override is honoured even when it is null, since that is how a user says
   * "ignore this column" about one the server did match.
   */
  mappedColumn(sheet: ImportSheet, sourceColumn: string): string | null {
    const override = this._mapping()[sheet];
    if (override && sourceColumn in override) {
      return override[sourceColumn];
    }
    return this.suggestions().get(sheet)?.get(sourceColumn) ?? null;
  }

  /**
   * Required columns of a sheet that nothing feeds, recomputed against the user's overrides.
   *
   * Recomputed client-side rather than re-fetched: the mapping step has to react as the user changes a
   * dropdown, and a round trip per keystroke-equivalent would make it feel broken. The server checks
   * the same thing again at validation, which is the answer that counts.
   */
  missingRequiredOf(sheet: ImportSheet): readonly string[] {
    const preview = this._preview();
    const sheetPreview = preview?.sheets.find((entry) => entry.sheet === sheet);
    if (!preview || !sheetPreview) {
      return [];
    }
    const fed = new Set<string>();
    for (const column of sheetPreview.columns) {
      const canonical = this.mappedColumn(sheet, column.sourceColumn);
      if (canonical) {
        fed.add(canonical);
      }
    }
    return (preview.schema[sheet] ?? [])
      .filter((column) => column.required && !fed.has(column.name))
      .map((column) => column.name);
  }

  setTimeBase(periodLength: Duration, horizonPeriods: number, roundingPolicy: RoundingPolicy): void {
    this._timeBase.set({ periodLength, horizonPeriods, roundingPolicy, declared: true });
  }

  // -------------------------------------------------------------------- step 3

  /** Step 2 → 3: the dry run. `validateOnly=true`, so nothing is created. */
  async validate(): Promise<void> {
    await this.submit(true, 'Could not validate the import.');
  }

  /** Step 3 confirm: the same request, committing. */
  async confirm(): Promise<ImportReport | null> {
    await this.submit(false, 'Could not create the network.');
    const report = this._report();
    return report?.committed ? report : null;
  }

  // ------------------------------------------------------- step 4, batch only (FR-28)

  /**
   * Import the batch: **one request per file, sequenced, baseline first** (FR-28).
   *
   * ## Why it is a sequence here and not a batch endpoint
   *
   * A batch of files is *N* of these requests, sequenced by the wizard
   * rather than a batch endpoint: the two-stage report is per network and so is the transaction.
   * There is nothing a `POST /networks/import/batch` could add - it would still validate each file on
   * its own, still commit each on its own, and still have to answer with N reports - and it would put
   * the sequencing rule below on the far side of an HTTP call, where the wizard could no longer show
   * it happening.
   *
   * ## The baseline first, and what that buys
   *
   * `baseNetworkId` names a network that has to exist, so the baseline goes first and its id is what
   * every *variant* request then carries. That edge is written inside the same transaction that
   * creates the network, so a variant is never briefly a network with no edge - which is why
   * this loop has nothing to clean up after a failure and why the provenance tree can be read while
   * the batch is still running.
   *
   * ## What one file's failure does to the others: as little as possible
   *
   * Nothing stops the sequence. A refused file is content, not an error - `_error` is deliberately
   * never written here, exactly as `NetworksStore.deleteMany` never writes its own - and the run
   * carries on to the next file. The *only* way one file reaches another is {@link attemptable}: a
   * variant of a baseline that was not created has nothing to point at and is **not attempted**,
   * rather than being imported bare as an independent network. Independent files are unaffected.
   *
   * ## Written as it happens
   *
   * Every file is seeded `PENDING` before the first request, and each is rewritten the moment its
   * answer arrives. So the report is accurate at every moment rather than only at the end, and a run
   * abandoned part-way - a navigation, a backend that stopped answering - leaves an honest account of
   * what exists. That is `deleteMany`'s discipline, for the same reason.
   */
  async importBatch(): Promise<void> {
    const projectId = this._projectId();
    const plan = this.plan();
    if (projectId === null || !plan || !plan.steps.length || this._busy()) {
      return;
    }

    // Order of the report: the order the batch acts in, then the files that were never sent.
    const outcomes: BatchOutcome[] = [
      ...plan.steps.map((step) => pendingOutcome(step)),
      ...plan.unnamed.map(
        (row): BatchOutcome => ({
          fileName: row.fileName,
          name: null,
          role: row.role,
          status: BatchStatus.NOT_ATTEMPTED,
          report: null,
          network: null,
          message: UNNAMED_REASON,
        }),
      ),
    ];
    this._busy.set(true);
    this._error.set(null);
    this._batch.set([...outcomes]);

    let baseNetworkId: Id | null = null;

    for (const [index, step] of plan.steps.entries()) {
      if (!attemptable(step, baseNetworkId)) {
        outcomes[index] = {
          ...outcomes[index],
          status: BatchStatus.NOT_ATTEMPTED,
          message: NOT_ATTEMPTED_REASON,
        };
        this._batch.set([...outcomes]);
        continue;
      }

      outcomes[index] = await this.importOne(step, baseNetworkId);
      if (outcomes[index].status === BatchStatus.CREATED && step.role === BatchRole.BASELINE) {
        baseNetworkId = outcomes[index].network?.id ?? outcomes[index].report?.networkId ?? null;
      }
      // A copy per answer: the report is a signal and the reader is watching it fill.
      this._batch.set([...outcomes]);
    }

    this._busy.set(false);
  }

  /** One file of the batch. Never throws: every failure becomes this file's own outcome. */
  private async importOne(step: BatchStep, baseNetworkId: Id | null): Promise<BatchOutcome> {
    const pending = pendingOutcome(step);
    try {
      const report = await firstValueFrom(
        this.api.post<ImportReport>(
          '/networks/import',
          this.formData([step.file], false, {
            name: step.name,
            baseline: step.setsBaselineFlag,
            baseNetworkId: step.needsBase ? baseNetworkId : null,
          }),
        ),
      );
      return {
        ...pending,
        status: report.committed ? BatchStatus.CREATED : BatchStatus.REFUSED,
        report,
        network: report.network ?? null,
        message: null,
      };
    } catch (failure) {
      // The request itself was refused rather than the *data* - a base network in another project
      // (422 `BASE_NETWORK_OUT_OF_SCOPE`), a second baseline (409 `BASELINE_ALREADY_SET`), a name the
      // server would not take. There is no report to render, so the problem detail is the account,
      // shown against this file rather than raised as the wizard's banner.
      return {
        ...pending,
        status: BatchStatus.REFUSED,
        message: problemMessage(failure, 'The server refused this file.'),
      };
    }
  }

  backToUpload(): void {
    this._preview.set(null);
    this._report.set(null);
    this._mapping.set({});
    this._timeBase.set(null);
    this._roles.set(null);
    this._batch.set(null);
    this._error.set(null);
  }

  backToMapping(): void {
    this._report.set(null);
    this._roles.set(null);
    this._batch.set(null);
    this._error.set(null);
  }

  /**
   * Back from a batch report to the roles step.
   *
   * Offered only while nothing was created - the report step gates it - because re-running a batch
   * that already made networks would make them again under the next version number rather than
   * replacing them. Fixing one file and importing that file is the remedy the report names.
   */
  backToRoles(): void {
    this._batch.set(null);
    this._error.set(null);
  }

  private async submit(validateOnly: boolean, failureMessage: string): Promise<void> {
    const projectId = this._projectId();
    if (projectId === null || !this._files().length) {
      return;
    }
    this._busy.set(true);
    this._error.set(null);
    try {
      const report = await firstValueFrom(
        this.api.post<ImportReport>(
          '/networks/import',
          this.formData(this._files(), validateOnly),
        ),
      );
      this._report.set(report);
    } catch (failure) {
      // A 4xx here is a malformed request, not a rejected file - a validation failure comes back as a
      // 200 carrying the report. So this really is an error banner rather than report content.
      this._error.set(problemMessage(failure, failureMessage));
    } finally {
      this._busy.set(false);
    }
  }

  /** The name of the network already carrying the project's baseline flag, for the roles step. */
  private describeExistingBaseline(): string | null {
    const existing = this._existingBaseline();
    return existing ? `${existing.name} v${existing.version}` : null;
  }

  /**
   * The multipart body.
   *
   * `FormData` deliberately, with no `Content-Type` header set: the browser has to choose the multipart
   * boundary, and setting the header by hand omits it and makes the request unparseable server-side.
   *
   * **`files` is a parameter rather than the store's list**, because FR-28 has three callers with
   * three answers: the preview posts one file of a batch and all of a CSV set, a single import posts
   * everything, and a batch import posts one file at a time. **`target` overrides what the wizard's
   * own fields would have said** - a batch takes its name from the file, its `baseline` from the
   * plan and its `baseNetworkId` from the network the baseline just created. Omit it and the body is
   * byte-for-byte the one this method has always built, which is what keeps the single-network path
   * out of the diff.
   */
  private formData(
    files: readonly File[],
    validateOnly?: boolean,
    target?: { name: string; baseline: boolean; baseNetworkId: Id | null },
  ): FormData {
    const form = new FormData();
    for (const file of files) {
      form.append('files', file, file.name);
    }
    if (validateOnly === undefined) {
      // The preview endpoint takes only the files and an optional mapping.
      if (Object.keys(this._mapping()).length) {
        form.append('mapping', JSON.stringify(this._mapping()));
      }
      return form;
    }

    form.append('projectId', String(this._projectId()));
    form.append('name', target ? target.name : this._name().trim());
    form.append('baseline', String(target ? target.baseline : this._baseline()));
    if (target?.baseNetworkId != null) {
      // FR-28: recorded as a CONFIGURATION_VARIANT of that network, in the same transaction that
      // creates this one. Omitted entirely for an independent network - never sent empty,
      // which the server would read as a missing base rather than as no base.
      form.append('baseNetworkId', String(target.baseNetworkId));
    }
    form.append('validateOnly', String(validateOnly));
    if (Object.keys(this._mapping()).length) {
      form.append('mapping', JSON.stringify(this._mapping()));
    }

    const timeBase = this._timeBase();
    if (timeBase) {
      // Sent whenever the wizard has a clock on screen, confirmed or seeded from the file: the value
      // the user is looking at is the value that should be imported, and re-deriving it server-side
      // would make the confirmation decorative. One mapping and one clock for the whole batch:
      // files imported together are files of one shape.
      form.append('periodLengthValue', String(timeBase.periodLength.value));
      form.append('periodLengthUnit', timeBase.periodLength.unit);
      form.append('horizonPeriods', String(timeBase.horizonPeriods));
      form.append('roundingPolicy', timeBase.roundingPolicy);
    }
    return form;
  }
}

/** One row of the batch report before its request has been answered. */
function pendingOutcome(step: BatchStep): BatchOutcome {
  return {
    fileName: step.file.name,
    name: step.name,
    role: step.role,
    status: BatchStatus.PENDING,
    report: null,
    network: null,
    message: null,
  };
}

/** A set without one member, leaving the original alone. */
function without(values: ReadonlySet<number>, value: number): ReadonlySet<number> {
  const next = new Set(values);
  next.delete(value);
  return next;
}

/**
 * A first guess at the network's name, from the upload.
 *
 * A workbook is named after what it holds, so its filename is a good guess. A CSV set is named after
 * the schema - `nodes.csv` says nothing - so there is nothing to guess from and the field is left for
 * the user. Guessing "nodes" would be worse than blank: it looks deliberate.
 *
 * The derivation itself is `file-names.networkNameFromFileName`, which is also what FR-28 names every
 * file of a batch by: a workbook imported alone and the same workbook imported in a batch must not
 * end up with two different names.
 */
function suggestName(files: readonly File[]): string {
  const workbook = files.find((file) => isWorkbook(file.name));
  return workbook ? (networkNameFromFileName(workbook.name) ?? '') : '';
}
