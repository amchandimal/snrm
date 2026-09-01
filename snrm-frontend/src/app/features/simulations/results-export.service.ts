import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiService, QueryParams } from '../../core/api.service';
import { Id, TabularExportFormat } from '../../core/models';
import { problemMessage } from '../../core/problem-details';

/**
 * Downloads a run's results or a comparison matrix as a spreadsheet.
 *
 * One service for both because the mechanics are identical and the reason they are not a link is the
 * same in both cases: the export endpoints are authenticated, so an `<a download href="/api/v1/…">`
 * would arrive without the bearer token and come back 401. The file is fetched as a blob through
 * `ApiService` - which is also what keeps the base URL and the token in one place - and
 * handed to the browser through a temporary object URL.
 *
 * The filename comes from the server's `Content-Disposition`, never from anything guessed here: the
 * server names the file after the network, its version and the run, and re-deriving that in the
 * client would give two answers to one question. `features/data-import/network-export.service.ts`
 * makes the same three decisions for the network export; the duplication is two small private
 * functions, and merging them would put a shared service above two features that have no other
 * reason to know about each other.
 */
@Injectable({ providedIn: 'root' })
export class ResultsExportService {
  private readonly api = inject(ApiService);

  private readonly _busy = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly busy = this._busy.asReadonly();
  readonly error = this._error.asReadonly();

  dismissError(): void {
    this._error.set(null);
  }

  /** `GET /simulations/{runId}/export` - the run, its metric suite and its curves. */
  async downloadRun(runId: Id, format: TabularExportFormat = 'xlsx'): Promise<void> {
    await this.download(
      `/simulations/${runId}/export`,
      { format },
      `run-${runId}.${extensionOf(format)}`,
      'Could not export this run.',
    );
  }

  /**
   * `GET /projects/{id}/comparison/export` - the matrix, its column legend and its caveats.
   *
   * Takes the same parameters the view was built with, so the file is "what I am looking at, saved"
   * rather than a second query with its own defaults - including the column order, which the server
   * preserves from `networkIds`.
   */
  async downloadComparison(
    projectId: Id,
    networkIds: readonly Id[],
    scenarioId: Id | null,
    format: TabularExportFormat = 'xlsx',
  ): Promise<void> {
    await this.download(
      `/projects/${projectId}/comparison/export`,
      {
        format,
        networkIds: networkIds.length ? [...networkIds] : undefined,
        scenarioId: scenarioId ?? undefined,
      },
      `project-${projectId}-comparison.${extensionOf(format)}`,
      'Could not export the comparison.',
    );
  }

  /**
   * The run-keyed matrix of FR-17, saved - `?runIds=` instead of `?networkIds=`.
   *
   * A separate method rather than a fourth parameter on {@link downloadComparison}, because the two
   * selectors are mutually exclusive on the server and a signature that accepts both would let a
   * caller build the 400 the endpoint exists to refuse.
   */
  async downloadRunComparison(
    projectId: Id,
    runIds: readonly Id[],
    format: TabularExportFormat = 'xlsx',
  ): Promise<void> {
    await this.download(
      `/projects/${projectId}/comparison/export`,
      { format, runIds: [...runIds] },
      `project-${projectId}-comparison.${extensionOf(format)}`,
      'Could not export the comparison.',
    );
  }

  private async download(
    path: string,
    params: QueryParams,
    fallbackName: string,
    fallbackError: string,
  ): Promise<void> {
    this._busy.set(true);
    this._error.set(null);
    try {
      const response = await firstValueFrom(this.api.download(path, { params }));
      const body = response.body;
      if (!body) {
        this._error.set('The export came back empty.');
        return;
      }
      save(body, filenameOf(response.headers.get('Content-Disposition'), fallbackName));
    } catch (failure) {
      this._error.set(problemMessage(failure, fallbackError));
    } finally {
      this._busy.set(false);
    }
  }
}

/** `csv` produces a zip of the tables, since a CSV export of three tables is three files. */
function extensionOf(format: TabularExportFormat): string {
  return format === 'csv' ? 'zip' : 'xlsx';
}

/**
 * The filename from `Content-Disposition`, falling back to something predictable.
 *
 * Reads `filename*` (RFC 5987, percent-encoded UTF-8) before plain `filename`: the server sets both,
 * and only the former survives a network named with an accent.
 */
function filenameOf(header: string | null, fallback: string): string {
  if (!header) {
    return fallback;
  }
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1].trim());
    } catch {
      // A malformed header is not worth failing a download over.
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header);
  return plain ? plain[1].trim() : fallback;
}

/** Hands a blob to the browser's downloader, then releases the object URL. */
function save(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.rel = 'noopener';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // Revoked on the next tick: revoking synchronously can cancel the download in some browsers.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
