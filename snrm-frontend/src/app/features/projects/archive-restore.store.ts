import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { ArchiveReport } from '../../core/models';
import { problemMessage } from '../../core/problem-details';
import {
  CountRow,
  ShapedFinding,
  countRows,
  // Aliased because two of the signals below carry the same names. A bare identifier inside a field
  // initialiser resolves to the module scope rather than to the class, so this works either way -
  // but a reader should not have to know that to be sure which one is being called.
  engineWarning as toEngineWarning,
  provenanceNote as toProvenanceNote,
  restoreHeadline,
  shapeFindings,
} from './archive-report';

/**
 * Restoring a project archive - `POST /projects/archive/import`.
 *
 * ## Why a store of its own rather than a few more signals on `ProjectsStore`
 *
 * `ProjectsStore` owns the list: an array of `Project`, a load state, and one error banner for the
 * screen. This owns a `File`, a report that outlives the request that produced it, and a failure
 * that is *content* rather than a banner. Three reasons to keep them apart, and the third is the one
 * that decides it:
 *
 * 1. **The lifetimes differ.** A `File` is not serialisable and must not outlive the page - the same
 *    reason `DataImportStore` documents for holding its own. `ProjectsStore` is `providedIn: 'root'`
 *    and is meant to persist; a half-chosen upload surviving a navigation to a project and back
 *    would be a bug in a store whose whole job is a stable list.
 * 2. **The failures are different questions.** `ProjectsStore.error` means "the list is wrong".
 *    `ARCHIVE_UNREADABLE` means "this file is not an archive" and belongs beside the drop target
 *    that produced it, not above the table.
 * 3. **A restore *creates a project*, so `ProjectsStore` is its consumer.** Folding the two together
 *    would have the list store call itself to refresh; separate, the list simply reloads when this
 *    one reports success, which is the ordinary direction of the dependency.
 *
 * The counterpart export is a plain download service (`project-archive.service.ts`), because a blob
 * with a busy flag is what `NetworkExportService` already is and holds no state worth a store.
 *
 * ## The report is content, and so is the failure
 *
 * A restore answers **201** with the report even when it has findings - it created a project, and
 * the findings qualify it. Only an unreadable archive is a 4xx (422 `ARCHIVE_UNREADABLE`),
 * and that arrives as `problem+json`, so {@link error} carries the server's own sentence through
 * `problemMessage` rather than a generic toast: an archive refused for a format version this build
 * cannot read and one refused for not being a zip need different answers from the reader.
 */
@Injectable({ providedIn: 'root' })
export class ArchiveRestoreStore {
  private readonly api = inject(ApiService);

  private readonly _file = signal<File | null>(null);
  private readonly _name = signal('');
  private readonly _report = signal<ArchiveReport | null>(null);
  private readonly _busy = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly file = this._file.asReadonly();
  readonly name = this._name.asReadonly();
  readonly report = this._report.asReadonly();
  readonly busy = this._busy.asReadonly();
  readonly error = this._error.asReadonly();

  /** `shared/file-drop` takes a list; there is only ever one archive in it. */
  readonly files = computed(() => {
    const file = this._file();
    return file ? [file] : [];
  });

  readonly canRestore = computed(() => this._file() !== null && !this._busy());

  // ------------------------------------------------------------- the shaped report
  //
  // Derived here rather than in the component so the view is a template over signals and the rules
  // stay in the pure module beside them (`archive-report.ts`, which has the spec).

  readonly findings = computed<readonly ShapedFinding[]>(() => {
    const report = this._report();
    return report ? shapeFindings(report.findings) : [];
  });

  readonly headline = computed(() => {
    const report = this._report();
    return report ? restoreHeadline(report) : null;
  });

  /** The engine-mismatch sentence, or null when the archived runs came from this engine. */
  readonly engineWarning = computed(() => {
    const report = this._report();
    return report ? toEngineWarning(report) : null;
  });

  readonly counts = computed<readonly CountRow[]>(() => {
    const report = this._report();
    return report ? countRows(report) : [];
  });

  readonly provenanceNote = computed(() => {
    const report = this._report();
    return report ? toProvenanceNote(report) : null;
  });

  // ------------------------------------------------------------------- the upload

  /**
   * Takes the archive from `shared/file-drop`.
   *
   * **Replaces rather than accumulates**, unlike the import wizard's upload step. There the five
   * canonical CSV files are one logical upload and a forgotten `node_products.csv` should
   * be addable; here a second file cannot be part of the same restore, so accumulating would leave
   * the user to work out which of two zips is about to be sent.
   */
  addFiles(files: readonly File[]): void {
    const file = files[files.length - 1];
    if (!file) {
      return;
    }
    this._file.set(file);
    this._error.set(null);
    // A report on screen belongs to the previous file. Dropping it is what keeps "the report
    // describes the file named above it" true without a second flag to check.
    this._report.set(null);
  }

  removeFile(): void {
    this._file.set(null);
  }

  /** A name for the restored project; blank takes the archived one. */
  setName(name: string): void {
    this._name.set(name);
  }

  dismissError(): void {
    this._error.set(null);
  }

  /** Clears everything - called when the report is dismissed, and on leaving the list. */
  reset(): void {
    this._file.set(null);
    this._name.set('');
    this._report.set(null);
    this._error.set(null);
    this._busy.set(false);
  }

  /**
   * `POST /projects/archive/import` (multipart).
   *
   * Resolves with the report on success and null on failure, so a caller can reload the project
   * list without watching the signals. The file is deliberately **kept** afterwards: the archive
   * that was restored is the caption of the report on screen, and clearing it would leave a report
   * describing a file the page no longer names.
   */
  async restore(): Promise<ArchiveReport | null> {
    const file = this._file();
    if (!file || this._busy()) {
      return null;
    }
    this._busy.set(true);
    this._error.set(null);
    try {
      const report = await firstValueFrom(
        this.api.post<ArchiveReport>('/projects/archive/import', this.formData(file)),
      );
      this._report.set(report);
      return report;
    } catch (failure) {
      // The server's own sentence, verbatim: ARCHIVE_UNREADABLE says *why* it could not be read -
      // not a zip, no bundle.json, or a format version written by a later build than this one - and
      // each of those has a different remedy.
      this._error.set(problemMessage(failure, 'Could not read that project archive.'));
      this._report.set(null);
      return null;
    } finally {
      this._busy.set(false);
    }
  }

  /**
   * The multipart body.
   *
   * `FormData` deliberately, with no `Content-Type` header set: the browser has to choose the
   * multipart boundary, and setting the header by hand omits it and makes the request unparseable
   * server-side. The same trap `DataImportStore` records.
   */
  private formData(file: File): FormData {
    const form = new FormData();
    form.append('file', file, file.name);
    const name = this._name().trim();
    if (name) {
      // Omitted when blank rather than sent empty: the backend takes the archived name in that case,
      // and suffixes it if this user already has one by that name (`PROJECT_RENAMED`).
      form.append('name', name);
    }
    return form;
  }
}
