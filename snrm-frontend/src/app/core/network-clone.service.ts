import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiService } from './api.service';
import { ForkRequest, cloneBody } from './fork-request';
import { ConfigurationVariant, Id } from './models';

/**
 * The one caller of `POST /networks/{id}/clone` (FR-09, FR-26).
 *
 * Two surfaces fork a network: the editor's fork prompt, which appears when an edit
 * meets the freeze, and - since FR-26 - **Duplicate network** on a project dashboard row, which is
 * the same fork made without opening the network first. This service is why that is one request
 * path rather than two, following `core/RegionNodesService`, which exists for exactly this reason:
 * one endpoint, two readers, and a second implementation would be free to disagree with the first.
 *
 * What it prevents is not the `api.post` line - that is cheap to write twice - but the body rule of
 * `cloneBody`. A caller that sent `{"name": null}` instead of `{}` would get the same answer today
 * and a different one the moment the backend distinguishes them, and a caller that sent
 * `{"leverChanges": {}}` would put an empty annotation in the comparison view where the researcher
 * recorded none.
 *
 * ## Deliberately stateless
 *
 * No `busy` signal and no `error` signal, unlike `NetworkExportService` beside it. A download has
 * nowhere to put a failure and no result anyone keeps, so the service holds both; a clone has a
 * **result** - the variant - and the two callers do quite different things with it. The editor
 * navigates into the copy and clears its undo stack; the dashboard splices the new row into its
 * list, re-reads the provenance tree and stays where it is. Each already owns an error surface
 * shaped for its own screen (`NetworkEditorStore.actionError`, `NetworksStore.error`), so the
 * failure is left to propagate rather than being caught into a third one nobody renders.
 */
@Injectable({ providedIn: 'root' })
export class NetworkCloneService {
  private readonly api = inject(ApiService);

  /**
   * Forks a network into a configuration variant.
   *
   * Answers the `CONFIGURATION_VARIANT` row rather than the copy alone, because the edge is the
   * point: it is what puts the copy under its base in the provenance tree, and
   * `variant.network` is the copy for callers that only want that. `generatedBy` is always `MANUAL`
   * - the backend refuses to let a client claim `SEARCH`, which belongs to Phase 2's engine.
   *
   * **Ungated on the freeze, and deliberately so.** A network frozen by a run clones like
   * any other: the freeze covers *edits*, and reading a configuration to copy it is not one - it is
   * the remedy the freeze itself advertises.
   */
  clone(networkId: Id, request: ForkRequest): Observable<ConfigurationVariant> {
    return this.api.post<ConfigurationVariant>(`/networks/${networkId}/clone`, cloneBody(request));
  }
}
