import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Observable,
  catchError,
  concatMap,
  finalize,
  from,
  map,
  of,
  tap,
  throwError,
  toArray,
} from 'rxjs';

import { ApiService } from '../../core/api.service';
import { ForkRequest } from '../../core/fork-request';
import { Id, Network, NetworkRequest } from '../../core/models';
import { NetworkCloneService } from '../../core/network-clone.service';
import { problemCode, problemMessage } from '../../core/problem-details';
import { RenameResult, renameRequest } from './network-rename';
import {
  DeletionOutcome,
  reconcileSelection,
  selectionOf,
  toggled,
} from './network-selection';
import { LoadState } from './projects.store';

/**
 * Signal-based state for the networks of the project currently open on the dashboard
 * (FR-01).
 *
 * Holds one project's networks at a time - the dashboard is the only view that needs them until the
 * comparison feature arrives, and keeping a per-project cache before then would be state to
 * invalidate for no benefit.
 *
 * ## The selection lives here, beside the list it is reconciled against (FR-23)
 *
 * FR-23 asks that "a network that is no longer there leaves the selection with it, so an action can
 * never be aimed at a row that has gone". That is a rule about the *list*, and the list is here - so
 * the selection is here too, as a set of ids next to the rows they point at, rather than in a store
 * of its own.
 *
 * A separate selection store is the shape this repository reaches for when state has a different
 * lifetime or a different meaning from the list beside it: `ArchiveRestoreStore` holds a file and a
 * report that outlive their request, and `TopologicalMetricsStore` holds an *opinion about* a
 * network which must be able to fail without taking the canvas with it. A selection is neither. It
 * is a cursor into this list, meaningless without it and dead the moment it changes - exactly what
 * `deleting` already is, one row at a time. Put it anywhere else and reconciliation becomes a
 * component effect watching the list, which is a second thing to remember to mount and a window in
 * which the two disagree.
 *
 * Here it is structural instead: {@link showNetworks} is the only way the list is ever set, and it
 * reconciles the selection in the same update. There is no ordering to get right, and nothing that
 * changes the list can forget.
 */
@Injectable({ providedIn: 'root' })
export class NetworksStore {
  private readonly api = inject(ApiService);
  /**
   * `POST /networks/{id}/clone` - the request the editor's fork prompt has always made (FR-26).
   *
   * Injected rather than posted here, because there is one clone path in this application and the
   * editor is its other caller. See `core/network-clone.service.ts` for what a second one would be
   * free to get wrong.
   */
  private readonly clones = inject(NetworkCloneService);

  private readonly _projectId = signal<Id | null>(null);
  private readonly _networks = signal<readonly Network[]>([]);
  private readonly _state = signal<LoadState>('idle');
  private readonly _error = signal<string | null>(null);
  private readonly _creating = signal(false);
  private readonly _deleting = signal<Id | null>(null);
  private readonly _selected = signal<ReadonlySet<Id>>(new Set<Id>());
  private readonly _deleteReport = signal<readonly DeletionOutcome[] | null>(null);
  private readonly _deletingMany = signal<SetDeleteProgress | null>(null);
  private readonly _duplicating = signal<Id | null>(null);
  private readonly _duplicated = signal<DuplicateResult | null>(null);
  private readonly _renaming = signal<Id | null>(null);
  private readonly _renamed = signal<RenameResult | null>(null);
  private readonly _renameFailure = signal<RenameFailure | null>(null);

  readonly networks = this._networks.asReadonly();
  readonly state = this._state.asReadonly();
  readonly error = this._error.asReadonly();
  readonly creating = this._creating.asReadonly();
  /** Which network is being deleted, so its row can show progress without a per-row flag. */
  readonly deleting = this._deleting.asReadonly();

  /** Which network is being duplicated, so its row can spin without a per-row flag (FR-26). */
  readonly duplicating = this._duplicating.asReadonly();
  /**
   * The last duplicate this dashboard made - the copy and the network it was forked from (FR-26).
   *
   * FR-26 asks that the researcher not be navigated away: "the researcher is working in the table".
   * So the outcome of a duplicate is a *statement*, not a route change, and this is what the
   * statement is made from - a sentence above the table and a mark on the copy's own row, which is
   * how the new network is identifiable when it shares its base's name and differs only by a
   * version number.
   */
  readonly duplicated = this._duplicated.asReadonly();

  /** Which network is being renamed, so its row can spin without a per-row flag (FR-29). */
  readonly renaming = this._renaming.asReadonly();
  /**
   * The last rename this dashboard made - the row as it was, and as the server answered (FR-29).
   *
   * Kept for the same reason {@link duplicated} is, plus one of its own: the table sorts by name,
   * so a renamed row *moves*. A reader who pressed a button on the fourth row and found nothing
   * changed there is looking at a defect until a sentence says where it went.
   */
  readonly renamed = this._renamed.asReadonly();
  /**
   * Why the last rename was refused, or null - **content for the dialog, not the page banner**.
   *
   * Deliberately not `_error`. That signal means "the list is wrong" and raises the banner at the
   * top of the page; a refused rename means the list is right and one request was turned down, with
   * the field that caused it still on screen to be corrected. Same reading `deleteMany` takes of
   * its own per-network refusals.
   */
  readonly renameFailure = this._renameFailure.asReadonly();

  readonly isLoading = computed(() => this._state() === 'loading');
  readonly isEmpty = computed(() => this._state() === 'ready' && this._networks().length === 0);

  /** True once the project already has a baseline; a second one is rejected with 409. */
  readonly hasBaseline = computed(() => this._networks().some((network) => network.baseline));

  // ------------------------------------------------------ the selection (FR-23)

  /**
   * The selected networks, in table order and intersected with the list (FR-23).
   *
   * **Everything acts on this, never on the id set.** The intersection is what makes "an action can
   * never be aimed at a row that has gone" true even in the moment between a reload being issued
   * and its response reconciling the set: while the table is empty, so is this.
   */
  readonly selectedNetworks = computed(() => selectionOf(this._networks(), this._selected()));
  readonly selectedCount = computed(() => this.selectedNetworks().length);
  /** Every row of the list is ticked - the select-all box's checked state. */
  readonly allSelected = computed(
    () => this._networks().length > 0 && this.selectedCount() === this._networks().length,
  );
  /** Some but not all - the select-all box's indeterminate state. */
  readonly partlySelected = computed(() => this.selectedCount() > 0 && !this.allSelected());

  /** What a finished (or abandoned) set delete did, per network. Null until one has run. */
  readonly deleteReport = this._deleteReport.asReadonly();
  /** Progress of a set delete in flight, so the dialog can count rather than just spin. */
  readonly deletingMany = this._deletingMany.asReadonly();

  isSelected(networkId: Id): boolean {
    return this._selected().has(networkId);
  }

  setSelected(networkId: Id, on: boolean): void {
    this._selected.update((selected) => toggled(selected, networkId, on));
  }

  /**
   * The header's select-all - **every** row, frozen ones included.
   *
   * Not "every row a delete could take": the menu answers several questions (Delete, and the export
   * and comparison of FR-24/FR-25, for which the freeze is irrelevant), and a select-all that
   * quietly skipped frozen rows would make the split of FR-15 invisible at exactly the moment the
   * confirmation exists to show it.
   */
  toggleAll(on: boolean): void {
    this._selected.set(on ? new Set(this._networks().map((network) => network.id)) : new Set<Id>());
  }

  clearSelection(): void {
    this._selected.set(new Set<Id>());
  }

  dismissDeleteReport(): void {
    this._deleteReport.set(null);
  }

  dismissDuplicated(): void {
    this._duplicated.set(null);
  }

  dismissRenamed(): void {
    this._renamed.set(null);
  }

  /** Clears a refusal so the dialog opens on a clean field rather than on the last attempt's. */
  clearRenameFailure(): void {
    this._renameFailure.set(null);
  }

  dismissError(): void {
    this._error.set(null);
  }

  /** `GET /projects/{id}/networks`. */
  load(projectId: Id): void {
    if (this._projectId() !== projectId) {
      // A selection, a delete report and a duplicate or rename note all describe one project's
      // list. Carried into another project's dashboard they would be describing rows that are not
      // there - and the report would name networks the reader has never seen.
      this.clearSelection();
      this._deleteReport.set(null);
      this._duplicated.set(null);
      this._renamed.set(null);
      this._renameFailure.set(null);
    }
    this._projectId.set(projectId);
    this._state.set('loading');
    this.clearForLoad();
    this.api.get<Network[]>(`/projects/${projectId}/networks`).subscribe({
      next: (networks) => {
        this.showNetworks(sortNetworks(networks));
        this._error.set(null);
        this._state.set('ready');
      },
      error: (failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not load the networks of this project.'));
        this._state.set('error');
      },
    });
  }

  /**
   * `POST /projects/{id}/networks`.
   *
   * A project may hold at most one baseline - the network the comparison view measures the others
   * against - so a second one comes back as a 409 the caller surfaces verbatim.
   */
  create(projectId: Id, request: NetworkRequest): Observable<Network> {
    this._creating.set(true);
    return this.api.post<Network>(`/projects/${projectId}/networks`, request).pipe(
      tap((network) => {
        // A new row arrives unticked: the researcher selected what they selected, and a network
        // they have not seen yet joining the set would be an action aimed at a surprise.
        this.showNetworks(sortNetworks([...this._networks(), network]));
        this._error.set(null);
        this._state.set('ready');
      }),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not create the network.'));
        return throwError(() => failure);
      }),
      finalize(() => this._creating.set(false)),
    );
  }

  /**
   * `PUT /networks/{id}` - *Rename…* on a row (FR-29).
   *
   * ## The body is `renameRequest`'s, and that is the whole of the care this needs
   *
   * **`PUT /networks/{id}` replaces the name AND the baseline flag together.** A body carrying only
   * the name arrives with `baseline` defaulting to `false` - it is a primitive on the backend, not
   * a nullable - so a rename that forgot the flag would quietly un-baseline the project, taking the
   * column the comparison view measures every other network against, with nothing on screen having
   * said so. This is the one trap of FR-29, and the answer is the rule the product
   * catalogue already records for `PUT /products/{id}` ("editing is name and unit value together",
   * where an omitted `unitValue` arrives as 0 and zeroes every monetary metric weighted by it).
   *
   * So the body is built by `network-rename.renameRequest` and by nothing else, and it sources the
   * flag from `network.baseline` - the row's own state as the server last reported it - rather than
   * from a control, because the dialog does not offer to change it. Nothing in this method assembles
   * a `NetworkRequest`, deliberately: one place to read, one place to get wrong.
   *
   * ## Ungated on the freeze, exactly like {@link duplicate}
   *
   * Nothing here reads `editable` and the menu entry is gated on nothing. The freeze covers
   * what a result was computed *from* - nodes, links, per-product rows, the time base -
   * and neither the name nor the flag is among them; the backend's guard came off
   * `NetworkService.update` for exactly that reason. A network with a completed run against it
   * renames, and the run is untouched.
   *
   * ## One write, and the panels below the table follow it
   *
   * The answer replaces its row through {@link showNetworks}, the single writer, re-sorted - so the
   * selection of FR-23 is reconciled in the same update (the id did not change, so a ticked row
   * stays ticked and now reads its new name), and the dashboard's provenance effect re-runs because
   * `networks()` changed, which is what redraws the lineage under the network's new label. Neither
   * needs telling, and there is no reload: the row that changed is the row the server described.
   *
   * A refusal is written to {@link renameFailure} rather than to `_error`, and the caller keeps the
   * dialog open on it - the message belongs beside the field that caused it, and `_error` means
   * "the list is wrong", which a refused rename does not make it.
   */
  rename(network: Network, typedName: string): Observable<Network> {
    this._renaming.set(network.id);
    this._renameFailure.set(null);
    this._renamed.set(null);
    return this.api
      .put<Network>(`/networks/${network.id}`, renameRequest(network, typedName))
      .pipe(
        tap((updated) => {
          this.showNetworks(
            sortNetworks(this._networks().map((row) => (row.id === updated.id ? updated : row))),
          );
          this._renamed.set({ before: network, after: updated });
          this._state.set('ready');
        }),
        catchError((failure: unknown) => {
          this._renameFailure.set({
            message: problemMessage(failure, 'Could not rename this network.'),
            code: problemCode(failure),
          });
          return throwError(() => failure);
        }),
        finalize(() => this._renaming.set(null)),
      );
  }

  /**
   * `POST /networks/{id}/clone` - *Duplicate network* on a row (FR-26).
   *
   * ## It is a fork, and the table and the tree both have to say so
   *
   * The action calls the clone the editor's fork prompt has always called, so the copy is
   * a `CONFIGURATION_VARIANT` recorded against its base, and it appears in the provenance tree under
   * the network it came from - an untracked copy would be the one network in the tree whose parent
   * nobody could name. Two consequences land here.
   *
   * The copy joins the list through {@link showNetworks}, the single writer - so it is sorted into
   * place by `sortNetworks` (variants of one name together, in version order, which is why the copy
   * lands directly under its base) and the selection is reconciled in the same update, exactly as a
   * create is. **And that is also what refreshes the lineage**: the dashboard's provenance effect is
   * driven by `networks()` changing, so the tree re-reads itself without a manual refresh and
   * without this store knowing that `ProvenanceStore` exists. FR-26 asks for both surfaces to
   * reflect the duplicate, and this is one write producing both.
   *
   * The new row arrives **unselected**, like a create's: the researcher selected what they selected,
   * and a network they have not looked at joining the set would aim the next set action at a
   * surprise.
   *
   * ## Ungated on the freeze
   *
   * Nothing here reads `editable`, and the menu entry is gated on nothing either. The freeze covers
   * *edits*; a clone reads its source, which is the same rule FR-24's export follows and the reason
   * forking is the remedy the freeze itself advertises.
   *
   * A failure raises the page banner (`_error`) rather than becoming content, unlike
   * {@link deleteMany}'s per-network report: there is one request and one answer here, so "the
   * action failed" *is* the whole account of it.
   */
  duplicate(base: Network, request: ForkRequest): Observable<Network> {
    this._duplicating.set(base.id);
    this._duplicated.set(null);
    return this.clones.clone(base.id, request).pipe(
      map((variant) => variant.network),
      tap((copy) => {
        this.showNetworks(sortNetworks([...this._networks(), copy]));
        this._duplicated.set({ base, copy });
        this._error.set(null);
        this._state.set('ready');
      }),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not duplicate this network.'));
        return throwError(() => failure);
      }),
      finalize(() => this._duplicating.set(null)),
    );
  }

  /**
   * `DELETE /networks/{id}` - the network and everything beneath it (FR-15).
   *
   * Nodes, links and per-product rows cascade server-side. The row is dropped from the list only once
   * the server has confirmed: an optimistic removal would show the network gone in the one case where
   * it certainly is not, which is the 409 below.
   *
   * A network frozen by a simulation run comes back `NETWORK_IMMUTABLE` (409). The dashboard does not
   * offer the action in that state, so reaching it means the freeze happened between the page loading
   * and the click - the message says so rather than being swallowed.
   */
  delete(networkId: Id): Observable<void> {
    this._deleting.set(networkId);
    return this.api.delete<void>(`/networks/${networkId}`).pipe(
      tap(() => {
        this.showNetworks(this._networks().filter((network) => network.id !== networkId));
        this._error.set(null);
      }),
      catchError((failure: unknown) => {
        this._error.set(problemMessage(failure, 'Could not delete the network.'));
        return throwError(() => failure);
      }),
      finalize(() => this._deleting.set(null)),
    );
  }

  /**
   * Delete a set of networks, **one at a time**, reporting the outcome of each (FR-23).
   *
   * ## Why one request per network
   *
   * Because each is refused or accepted on its own terms and a per-network outcome is what the
   * reader needs. A network frozen between the list being read and this call being made
   * comes back 409 `NETWORK_IMMUTABLE` while its neighbours succeed, and that is a fact about *that*
   * network - not a reason to abandon the other four. The trade is deliberate and worth stating:
   * a bulk endpoint is the answer if a project ever holds enough networks for the request count to
   * matter, and nothing about the surface would change. If that day comes, `POST /networks/delete`
   * (or `DELETE /projects/{id}/networks?ids=`) replaces the `concatMap` below and returns this same
   * array of outcomes; the dialog, the split and the report are untouched. `product-usage.service.ts`
   * and `provenance.store.ts` each record the same shape of trade for their own sweeps.
   *
   * ## What is true at every moment, not just at the end
   *
   * `concatMap` keeps the requests sequential and the inner `catchError` keeps a refusal from
   * cancelling the ones behind it. Each accepted network leaves the list *as it is accepted*, and
   * the report is rewritten after every answer - so a sequence abandoned part-way (a navigation, a
   * closed tab, a backend that stopped answering) still leaves an accurate account of what went and
   * what did not, and a table that matches it. Nothing here is optimistic and nothing is deferred to
   * the end.
   *
   * `_error` is deliberately **not** written. That signal means "the list is wrong" and raises the
   * page banner; here a refusal is content, and the report below the toolbar is where it is read.
   */
  deleteMany(networks: readonly Network[]): Observable<readonly DeletionOutcome[]> {
    if (networks.length === 0 || this._deletingMany() !== null) {
      return of([]);
    }

    const outcomes: DeletionOutcome[] = [];
    this._deleteReport.set(null);
    this._deletingMany.set({ done: 0, total: networks.length });

    return from(networks).pipe(
      concatMap((network) => {
        this._deleting.set(network.id);
        return this.api.delete<void>(`/networks/${network.id}`).pipe(
          map(
            (): DeletionOutcome => ({
              network,
              status: 'deleted',
              message: null,
              code: null,
            }),
          ),
          catchError((failure: unknown) =>
            of<DeletionOutcome>({
              network,
              status: 'refused',
              message: problemMessage(failure, 'The server refused to delete this network.'),
              code: problemCode(failure),
            }),
          ),
          tap((outcome) => {
            outcomes.push(outcome);
            if (outcome.status === 'deleted') {
              this.showNetworks(this._networks().filter((row) => row.id !== outcome.network.id));
            }
            // A copy per answer: the report is a signal, and the reader is watching it fill.
            this._deleteReport.set([...outcomes]);
            this._deletingMany.set({ done: outcomes.length, total: networks.length });
          }),
        );
      }),
      toArray(),
      finalize(() => {
        this._deleting.set(null);
        this._deletingMany.set(null);
      }),
    );
  }

  /**
   * The only way the list is set - and therefore the only place the selection is reconciled
   * (FR-23).
   *
   * One update rather than two, so there is no moment in which the menu is enabled for a row that
   * has already gone. {@link reconcileSelection} answers with the same set instance when nothing was
   * dropped, so the common case wakes nothing up.
   */
  private showNetworks(networks: readonly Network[]): void {
    this._networks.set(networks);
    this._selected.update((selected) => reconcileSelection(selected, networks));
  }

  /**
   * Empties the table while a load is in flight, deliberately **without** reconciling.
   *
   * This is not a list - it is the absence of one, for as long as the request takes - and letting it
   * reconcile would discard the selection on every Refresh, which is the opposite of what FR-23
   * asks for: a reload keeps what is still there and drops only what is not. `selectedNetworks` is
   * empty meanwhile because it intersects with the rows on screen, so nothing can be acted on in
   * the gap either way.
   */
  private clearForLoad(): void {
    this._networks.set([]);
  }
}

/**
 * What a duplicate produced: the copy, and the network it was forked from (FR-26).
 *
 * Both, not just the copy, because the statement the dashboard makes afterwards is about the
 * *edge* - `Baseline v4 (#31) was forked from Baseline v1 (#7)` - which is the thing that
 * distinguishes this from having made an untracked copy.
 */
export interface DuplicateResult {
  readonly base: Network;
  readonly copy: Network;
}

/**
 * Why a rename was refused: the server's own sentence, and the code to key a remedy off (FR-29).
 *
 * Both, because the two do different jobs. The sentence is shown verbatim, and the code is
 * what `network-rename.renameRefusalNote` branches on - never the sentence, and here that matters
 * more than usual: the backend answers a schema conflict with a deliberately vague line, so the
 * useful half of the explanation is the one derived from the code.
 */
export interface RenameFailure {
  readonly message: string;
  readonly code: string | null;
}

/** How far a set delete has got, for the dialog's counter (FR-23). */
export interface SetDeleteProgress {
  /** Networks answered for, refusals included. */
  readonly done: number;
  readonly total: number;
}

/** Variants of one name sort together, newest version last. */
function sortNetworks(networks: readonly Network[]): readonly Network[] {
  return [...networks].sort((a, b) => a.name.localeCompare(b.name) || a.version - b.version);
}
