import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { ExportFormat, Id } from '../../core/models';
import { problemMessage } from '../../core/problem-details';

/**
 * Downloads a network in the canonical schema - the other half of the round trip.
 *
 * > "the same canonical schema is used by the export function, making round-tripping (export → edit in
 * > Excel → re-import as a new variant) a supported workflow."
 *
 * ## Why this is a blob and not a link
 *
 * `GET /networks/{id}/export` is authenticated, so an `<a download href="/api/v1/…">` would arrive
 * without the bearer token and come back 401. The file therefore has to be fetched as a blob through
 * `ApiService` - which is also what keeps the base URL and the token in one place - and handed
 * to the browser through a temporary object URL.
 *
 * The filename comes from the server's `Content-Disposition`, not from anything guessed here: the server
 * names the file after the network and its version, and re-deriving that in the client would give two
 * answers to one question.
 */
@Injectable({ providedIn: 'root' })
export class NetworkExportService {
  private readonly api = inject(ApiService);

  private readonly _busyFor = signal<Id | null>(null);
  private readonly _error = signal<string | null>(null);

  /** Which network is being exported, so a row can show a spinner without a per-row flag. */
  readonly busyFor = this._busyFor.asReadonly();
  readonly error = this._error.asReadonly();

  dismissError(): void {
    this._error.set(null);
  }

  /**
   * Fetches and saves the export.
   *
   * @param format `xlsx` for one workbook - the form that re-imports in a single upload - or `csv` for a
   *               zip of the five files, which is what a text diff of two variants needs
   */
  async download(networkId: Id, format: ExportFormat = 'xlsx'): Promise<void> {
    this._busyFor.set(networkId);
    this._error.set(null);
    try {
      const response = await firstValueFrom(
        this.api.download(`/networks/${networkId}/export`, { params: { format } }),
      );
      const body = response.body;
      if (!body) {
        this._error.set('The export came back empty.');
        return;
      }
      save(body, filenameOf(response.headers.get('Content-Disposition'), networkId, format));
    } catch (failure) {
      this._error.set(problemMessage(failure, 'Could not export this network.'));
    } finally {
      this._busyFor.set(null);
    }
  }
}

/**
 * The filename from `Content-Disposition`, falling back to something predictable.
 *
 * Reads `filename*` (RFC 5987, percent-encoded UTF-8) before plain `filename`: the server sets both, and
 * only the former survives a network named with an accent.
 */
function filenameOf(header: string | null, networkId: Id, format: ExportFormat): string {
  const fallback = `network-${networkId}.${format === 'csv' ? 'zip' : 'xlsx'}`;
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
