import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiService } from './api.service';
import { Id, RegionNodes } from './models';

/**
 * `GET /networks/{id}/region-nodes?region=…` - what a REGION event would actually strike.
 *
 * ## Why this is server-side, and why it is here
 *
 * A REGION target names a `node.region` tag and nothing on the event says which nodes that is.
 * Filtering the loaded nodes in the browser would answer the question - and would be a
 * **second implementation of the resolution**, free to drift from the one a simulation run will use.
 * A tag whose casing or trailing space differs, a node loaded before a retag, an editor holding a
 * partial set: each is a way for the highlight to disagree with the disruption. So the server
 * answers, always.
 *
 * It sits in `core/` for the same reason `lever-changes.ts` does: two features now ask the same
 * question - the scenario builder's event editor as a tag is typed, and the network
 * editor's disruption overlay when it resolves a region event onto the canvas (FR-16) -
 * and one refusal must not be previewed two different ways.
 *
 * Deliberately **stateless and uncached**. The answer changes the moment a node is retagged, and it
 * is one indexed query; a cache here would be a third place for the resolution to go stale. Callers
 * that need to hold an answer decide for themselves when to re-ask.
 */
@Injectable({ providedIn: 'root' })
export class RegionNodesService {
  private readonly api = inject(ApiService);

  /**
   * The nodes of `networkId` carrying `region`.
   *
   * An empty `nodes` is a valid answer rather than an error: the tag may be one the scenario picked
   * up against another variant. Saving such an event is refused with
   * `EVENT_TARGET_INVALID`; resolving it first is how both surfaces warn before the refusal.
   */
  resolve(networkId: Id, region: string): Observable<RegionNodes> {
    return this.api.get<RegionNodes>(`/networks/${networkId}/region-nodes`, {
      params: { region },
    });
  }
}
