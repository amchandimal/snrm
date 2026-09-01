import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { Id } from '../../core/models';
import { problemMessage } from '../../core/problem-details';

/**
 * Downloads a whole experiment as one file - `GET /projects/{id}/archive`.
 *
 * > "a project export/import (JSON bundle) so an entire experiment is archivable alongside the
 * > thesis"
 *
 * ## Why this is not the network export
 *
 * `GET /networks/{id}/export` writes a network's *inputs* - structure, units, layout - and nothing
 * else: no scenario, no run, no seed, no result. A reader handed only that can rebuild what was
 * modelled but not what was found. The archive carries both halves: `bundle.json` plus one XML
 * document per network, byte-identical to what the network export returns, so a single network
 * can still be lifted out of the zip and imported on its own.
 *
 * ## Why it is a blob and not a link
 *
 * The endpoint is authenticated, so an `<a download href="/api/v1/…">` would arrive without the
 * bearer token and come back 401. The file is fetched as a blob through `ApiService` - which is also
 * what keeps the base URL and the token in one place - and handed to the browser through a
 * temporary object URL. `features/data-import/network-export.service.ts` and
 * `features/simulations/results-export.service.ts` make the same three decisions, including reading
 * the filename from `Content-Disposition` rather than guessing it: the server names the file after
 * the project (`<project>-archive.zip`), and re-deriving that here would give two answers to one
 * question.
 *
 * A third small copy of `filenameOf`/`save` rather than a shared module, for the reason
 * `ResultsExportService` states about the second: merging them would put a shared service above
 * three features that have no other reason to know about each other.
 *
 * ## The subset is this service with a query parameter (FR-24)
 *
 * `GET /projects/{id}/archive?networkIds=…` writes the same archive narrowed to the selected
 * networks, so {@link ProjectArchiveService.download} takes an optional selection rather
 * than a second method existing beside it - and a second *download path* was never on the table.
 * Everything a download needs to get right is here already and is easy to get subtly wrong twice:
 * the bearer token, the blob, the object URL revoked on the next tick, and above all the filename
 * read from `Content-Disposition` rather than guessed. The server names a subset
 * `<project>-3-networks-archive.zip` - a second implementation would have had to know that, and
 * would have been the copy that did not when the server's answer changed.
 *
 * The busy flag stays keyed by project rather than gaining a selection of its own: two exports of
 * one project cannot be in flight at once, because the control that starts them is a modal.
 */
@Injectable({ providedIn: 'root' })
export class ProjectArchiveService {
  private readonly api = inject(ApiService);

  /** Which project is being archived, so a button can show a spinner without a per-row flag. */
  private readonly _busyFor = signal<Id | null>(null);
  private readonly _error = signal<string | null>(null);

  readonly busyFor = this._busyFor.asReadonly();
  readonly error = this._error.asReadonly();

  dismissError(): void {
    this._error.set(null);
  }

  /**
   * Fetches and saves the archive.
   *
   * One format only, unlike the network export: an archive is not a spreadsheet a researcher edits
   * and re-imports, it is the file the restore reads, and offering a second shape would invite a
   * variant that cannot be restored.
   *
   * @param networkIds carry only these networks of the project (FR-24). **Omit it and the request
   *   is the one this method has always made** - no `networkIds` parameter is sent at all, so the
   *   server takes its whole-project path and answers with the archive it always did. An empty
   *   array is treated as omitted here rather than sent: the server refuses an empty selection with
   *   a 400 on purpose, and a caller that reached this method with nothing selected has a bug the
   *   round trip should not be spent diagnosing.
   */
  async download(projectId: Id, networkIds?: readonly Id[]): Promise<void> {
    this._busyFor.set(projectId);
    this._error.set(null);
    try {
      const selection = networkIds?.length ? { networkIds: [...networkIds] } : undefined;
      const response = await firstValueFrom(
        this.api.download(`/projects/${projectId}/archive`, { params: selection }),
      );
      const body = response.body;
      if (!body) {
        this._error.set('The archive came back empty.');
        return;
      }
      // The server's name, not ours - it is the one that knows a subset from a whole project.
      save(body, filenameOf(response.headers.get('Content-Disposition'), projectId));
    } catch (failure) {
      this._error.set(
        problemMessage(
          failure,
          networkIds?.length
            ? 'Could not export the selected networks as a project.'
            : 'Could not archive this project.',
        ),
      );
    } finally {
      this._busyFor.set(null);
    }
  }
}

/**
 * The filename from `Content-Disposition`, falling back to something predictable.
 *
 * Reads `filename*` (RFC 5987, percent-encoded UTF-8) before plain `filename`: the server sets both,
 * and only the former survives a project named with an accent.
 */
function filenameOf(header: string | null, projectId: Id): string {
  const fallback = `project-${projectId}-archive.zip`;
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
