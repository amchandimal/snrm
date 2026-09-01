import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { forkRequestFrom } from '../../../core/fork-request';
import { flattenLevers } from '../../../core/lever-changes';
import {
  ExportFormat,
  Id,
  Network,
  NetworkRequest,
  Project,
  VariantOrigin,
} from '../../../core/models';
import { problemMessage } from '../../../core/problem-details';
import { ConfirmDialogComponent } from '../../../shared/confirm-dialog/confirm-dialog.component';
import { compareBlocker, paneIdsParam } from '../../comparison/pane-grid';
import { NetworkExportService } from '../../data-import/network-export.service';
import { NetworkActionsMenuComponent } from '../network-actions-menu/network-actions-menu.component';
import { duplicateConfirm, duplicateOutcome } from '../network-duplicate';
import {
  renameBlocker,
  renameConfirm,
  renameOutcome,
  renameRefusalNote,
} from '../network-rename';
import {
  DeletionOutcome,
  SelectionSplit,
  deletionDetails,
  deletionHeadline,
  describeNetwork,
  frozenSelectionBlocker,
  outcomeNote,
  selectionDeleteConfirm,
  selectionExportConfirm,
  splitSelection,
} from '../network-selection';
import { NetworksStore } from '../networks.store';
import { ProductsStore } from '../products.store';
import { ProjectArchiveService } from '../project-archive.service';
import { ProjectsStore } from '../projects.store';
import { ProvenanceStore } from '../provenance.store';

/**
 * What `setDeleteConfirm` folds to while the set-delete dialog is closed.
 *
 * A computed cannot be conditional on its own template branch, and the alternative - a nullable
 * confirmation the template then has to assert non-null inside its own `@if` - trades one
 * unreachable object for an assertion on every field. Nothing built from this is ever rendered.
 */
const EMPTY_SPLIT: SelectionSplit = { deletable: [], blocked: [] };

/**
 * Project dashboard - the networks of one project (`features/projects`, FR-01).
 *
 * Each row opens in the network editor. The editor itself is a placeholder route for now
 * (the Cytoscape canvas comes later); wiring the navigation first means the route, the ids it
 * carries and the immutability signals are all in place before the canvas arrives.
 *
 * A network is created here rather than only through the API because the dashboard is otherwise
 * unreachable in a fresh install - there would be nothing to open.
 *
 * ## The table lists configurations; the tree beneath it says where they came from
 *
 * A project with nine variants has nine rows that share a name and differ in a version number, and
 * the version number does not say what was forked from what: `Baseline v4` may be a fork of `v1` or
 * of `v3`, and the two claims are different findings. `configuration_variant` records the answer
 * and the comparison view already annotates each *column* with the lever note taken at the
 * fork - but a column legend is not a lineage. The provenance panel is that lineage, with the same
 * note beside each row, so "what did I derive from this baseline, and why" is one glance rather than
 * a reconstruction. See `ProvenanceStore` for what it costs to read.
 *
 * ## A row is one action and a menu (FR-26)
 *
 * The row's controls grew one at a time - open, then three export formats, then delete - until the
 * thing a reader does most often was one button among five with no visual claim to being the first.
 * **Open in editor stays a button**; the three exports, the new **Duplicate network**
 * and Delete sit behind a single per-row menu, which is `network-actions-menu/` in its `row` scope
 * rather than a second dropdown - see that component for why it was generalised in place.
 *
 * Nothing that moved changed. Delete still asks for the owning project's name typed exactly
 * (FR-15), still does not appear at all on a frozen network - with the reason shown, which the menu
 * has room to do properly where a disabled button had only a tooltip - and still issues one
 * `DELETE` whose failure keeps the dialog open. Its wording is now `deletionDetails` from
 * `network-selection.ts`, shared with the set delete of FR-23, because a control that moves is a
 * control whose wording is about to drift.
 *
 * ## Duplicating is forking, and the tree is the test of it (FR-26)
 *
 * *Duplicate network* calls `POST /networks/{id}/clone` through `core/NetworkCloneService` - the
 * request the editor's fork prompt has always made, given a second caller rather than a second
 * implementation. So the copy is a `CONFIGURATION_VARIANT` recorded against its base and appears in
 * the lineage panel below under the network it came from; an untracked copy would be the one
 * network in that tree whose parent nobody could name.
 *
 * The dialog is `shared/confirm-dialog` with the two fields projected into its slot, not a
 * component of its own, and it is deliberately **not** the editor's fork prompt: that modal's body
 * is the freeze, the edit that will not be replayed and FR-20's discard branch, and its name field
 * is empty-means-inherit where this one is prefilled - none of which survives the trip to a table
 * where nothing was refused. What the two share is the part that has to agree, and it is shared
 * literally: `core/fork-request.forkRequestFrom` resolves both dialogs' fields, so a prefilled name
 * and a placeholder name leave the browser as one request.
 *
 * After it succeeds the researcher stays here (FR-26: "the researcher is working in the table").
 * The copy joins the table through `NetworksStore.showNetworks`, which sorts it beside its base and
 * - because the provenance effect below is driven by the list changing - re-reads the lineage with
 * no manual refresh. `NetworksStore.duplicated` is what names it afterwards, in a sentence above
 * the table and a mark on its row, since a copy that shares its base's name and differs by a
 * version number is otherwise hard to pick out of nine rows that all do.
 *
 * ## A name is editable in the table (FR-29)
 *
 * The row menu's first entry is *Rename…*: a name arrives from a typed field at creation or from a
 * file name at import (FR-28), and neither is a decision the researcher has necessarily made yet.
 * **It is ungated on the freeze**, which is the headline case rather than an edge one - the freeze
 * covers what a result was computed *from*, and a name is not among them, so the
 * network most in need of a better name is routinely one that has already been evaluated.
 *
 * One trap carries the whole feature and it is `network-rename.renameRequest`'s:
 * **`PUT /networks/{id}` replaces the name and the baseline flag together**, so a rename that sent
 * only the name would quietly un-baseline the project. The request is built in that one function,
 * from the row's own state - the same rule the product catalogue records for `PUT /products/{id}`
 * ("name and unit value together"), at its second occurrence.
 *
 * What lives here is the dialog and what follows it: a refusal shown *inside* the dialog, beside the
 * field that caused it, with the dialog left open so the name can be corrected; and, on success, a
 * sentence above the table, because the table sorts by name and a renamed row moves.
 *
 * ## The table addresses a set as well as a row (FR-23)
 *
 * A checkbox per row, a select-all in the header, and an actions menu above the table that stays
 * disabled until something is ticked and then names the count. It answers a different question from
 * the row menu - *act on these five* - and replacing "delete this" with "select it, then choose
 * delete" would make the common case the longer one.
 *
 * The selection itself lives on `NetworksStore`, reconciled against the list every time the list is
 * set; see that store for why it is there and not in one of its own. What lives here is what the
 * *screen* does with it: the split of FR-15 that the confirmation shows before the phrase field, the
 * sentence shown instead of a dialog when nothing in the selection can go at all, and the
 * per-network report the sequence leaves behind. The wording of all three is
 * `features/projects/network-selection.ts` - pure and specced, like `core/run-discard.ts`, because
 * the part that has to be right is how an irreversible question is put.
 *
 * ## Exporting a selection as a standalone project (FR-24)
 *
 * The menu's second action writes the ticked networks out as one project archive - the ordinary
 * archive narrowed to them - through the **same** `ProjectArchiveService` the
 * whole-project card below the table uses, with `networkIds`. One download path, one place that
 * knows about bearer tokens, blobs and the `Content-Disposition` filename.
 *
 * It is confirmed rather than issued on the click, and the dialog has no typed phrase: nothing is
 * destroyed, so the friction of FR-15 would be friction spent teaching the user to type through it.
 * What the dialog is for is the two claims a reader cannot check from a menu entry - **it copies**,
 * and **a restore creates a new project, never a merge** - said in the sentences the restore card
 * already uses (`archive-rules.ts`), plus the list of what is about to travel. A frozen network is
 * exported exactly like an editable one; the freeze is about edits, and a copy is not
 * one, which is why this action has no split and no per-network outcome.
 */
@Component({
  selector: 'app-project-dashboard',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    ConfirmDialogComponent,
    NetworkActionsMenuComponent,
  ],
  templateUrl: './project-dashboard.component.html',
  styleUrl: './project-dashboard.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProjectDashboardComponent {
  private readonly route = inject(ActivatedRoute);
  /** Serialises the side-by-side URL (FR-25) - the router's own writer, never a hand-built string. */
  private readonly router = inject(Router);
  private readonly projects = inject(ProjectsStore);
  readonly store = inject(NetworksStore);
  readonly exports = inject(NetworkExportService);
  /**
   * The whole experiment as one file - `GET /projects/{id}/archive`.
   *
   * Beside the per-network exports rather than replacing them, because the two answer different
   * questions. A network export carries that network's *inputs* and is meant to be edited and
   * re-imported; the archive carries the inputs **and what was found** - scenarios, runs, seeds,
   * metric results, time series - and is meant to be kept. The matching import is on the project
   * list, since restoring creates a new project rather than adding to this one.
   */
  readonly archives = inject(ProjectArchiveService);
  /**
   * The project's catalogue, read here only to answer one question: is it empty?
   *
   * An empty catalogue is the first link in a chain that ends three screens away - no product means
   * no node can carry demand, and `POST /simulations` refuses a network with no demand at all
   * (`NETWORK_HAS_NO_DEMAND`). Discovering that at the launcher, after building a network,
   * is the worst place to discover it, so the dashboard spends one request to say it here instead.
   */
  readonly products = inject(ProductsStore);
  /** The fork forest of this project - `GET /networks/{id}/variants`. */
  readonly provenance = inject(ProvenanceStore);

  readonly projectId = signal<Id | null>(null);
  readonly project = signal<Project | null>(null);
  readonly projectError = signal<string | null>(null);

  readonly newNetwork = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(160)],
    }),
    baseline: new FormControl(false, { nonNullable: true }),
  });

  /** The tree rows, each with its lever note already flattened for rendering. */
  readonly provenanceRows = computed(() =>
    this.provenance.entries().map((entry) => ({
      entry,
      levers: flattenLevers(entry.variant?.leverChanges),
      // A candidate the Phase 2 configuration search persisted, rather than an editor fork.
      // Worth a badge: the two are read differently, and nothing else on screen says so.
      search: entry.variant?.generatedBy === VariantOrigin.SEARCH,
    })),
  );

  constructor() {
    // Route params rather than ngOnInit: navigating between two projects reuses this component,
    // and a params subscription reloads where a lifecycle hook would not fire again.
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const id = Number(params.get('projectId'));
      if (!Number.isFinite(id) || id <= 0) {
        this.projectError.set('That project id is not valid.');
        return;
      }
      this.projectId.set(id);
      // Dropped before the new project's list arrives: a tree left on screen from the previous
      // project would be read as this one's.
      this.provenance.reset();
      this.loadProject(id);
      this.store.load(id);
      this.products.load(id);
    });

    // Driven by the list arriving rather than by the route, because the sweep needs the networks
    // themselves and `NetworksStore.load` is fire-and-forget. It re-runs when the list changes,
    // which is what keeps the tree honest after a network is deleted out from under it.
    // `allowSignalWrites` because the store sets its state signals synchronously.
    effect(
      () => {
        const networks = this.store.networks();
        if (this.store.state() === 'ready') {
          void this.provenance.load(networks);
        }
      },
      { allowSignalWrites: true },
    );

    // The "everything you ticked is frozen" sentence names one selection (FR-23), and so does the
    // blocked-window link of FR-25 - its `?ids=` is the set as it was when the click happened.
    // Ticking or unticking anything, or a reload reconciling a row away, makes both statements about
    // a set that no longer exists, so they go with the change rather than being left to be re-read.
    effect(
      () => {
        this.store.selectedNetworks();
        this.selectionBlocker.set(null);
        this.blockedComparison.set(null);
      },
      { allowSignalWrites: true },
    );
  }

  /** Retries the provenance sweep alone - the network table beside it is already loaded. */
  reloadProvenance(): void {
    void this.provenance.load(this.store.networks());
  }

  createNetwork(): void {
    const projectId = this.projectId();
    if (!projectId || this.newNetwork.invalid || this.store.creating()) {
      this.newNetwork.markAllAsTouched();
      return;
    }
    const request: NetworkRequest = this.newNetwork.getRawValue();
    this.store.create(projectId, request).subscribe({
      next: () => this.newNetwork.reset({ name: '', baseline: false }),
      error: () => undefined,
    });
  }

  /** Route to the network editor for a network (placeholder until the canvas exists). */
  editorLink(network: Network): (string | number)[] {
    return ['/projects', network.projectId, 'networks', network.id, 'editor'];
  }

  /** Route to the three-step import wizard. */
  importLink(): (string | number)[] {
    return ['/projects', this.projectId() ?? 0, 'import'];
  }

  /**
   * Route to the project's product catalogue (FR-01).
   *
   * Beside the networks rather than inside one: a product is project-scoped, so every configuration
   * variant is a different structure over the same catalogue.
   */
  productsLink(): (string | number)[] {
    return ['/projects', this.projectId() ?? 0, 'products'];
  }

  /**
   * Route to the project's disruption scenarios.
   *
   * Beside the networks rather than inside one: a scenario is project-scoped, so it can be replayed
   * against every configuration variant, and the network its timeline is drawn against is
   * picked inside the builder.
   */
  scenariosLink(): (string | number)[] {
    return ['/projects', this.projectId() ?? 0, 'scenarios'];
  }

  /** Route to the run launcher. Project-scoped: a run needs a network and a scenario. */
  simulationsLink(): (string | number)[] {
    return ['/projects', this.projectId() ?? 0, 'simulations'];
  }

  /**
   * Route to the comparison view (FR-10).
   *
   * Beside the network list because that is where the variants are: a comparison is about several of
   * them at once, so it belongs to the project rather than to any one network.
   */
  comparisonLink(): (string | number)[] {
    return ['/projects', this.projectId() ?? 0, 'comparison'];
  }

  /**
   * Downloads a network in the canonical import schema.
   *
   * Offered next to every network rather than only in the editor because the export is how a network
   * leaves the tool - for a spreadsheet edit and a re-import as a variant, or for the appendix of a
   * paper - and the dashboard is where a researcher is looking at the list of them.
   */
  exportNetwork(network: Network, format: ExportFormat): void {
    void this.exports.download(network.id, format);
  }

  /**
   * Downloads the whole project as one archive.
   *
   * On the dashboard rather than on the project list because an archive is *this* project - the
   * control belongs where the thing it copies is. Its counterpart lives on the list for the mirror
   * reason: a restore has no project to belong to until it has made one.
   */
  exportArchive(): void {
    const projectId = this.projectId();
    if (projectId) {
      void this.archives.download(projectId);
    }
  }

  // ------------------------------------------------------------------- deletion

  /** The network the delete dialog is open for, or null (FR-15). */
  readonly pendingDelete = signal<Network | null>(null);

  /**
   * What the user has to type to enable the deletion: the owning project's name.
   *
   * Not the network's - a network shares its name with every variant of it, so typing
   * "Baseline" would not say *which* Baseline is about to go. The project's name is unambiguous and is
   * on screen above the table to be read. The dialog names the network and its version separately, so
   * what is being deleted is never in doubt.
   */
  readonly deletePhrase = computed(() => this.project()?.name ?? '');

  /**
   * What goes with the network, spelled out before the fact rather than discovered after it.
   *
   * `deletionDetails(1)` - the same sentences the set delete of FR-23 lists, at a count of one
   * (FR-26). They were two near-copies until this control moved into the row menu; moving
   * a control is exactly when its wording drifts, and one irreversible act must not be described
   * two ways.
   */
  readonly deleteDetails: readonly string[] = deletionDetails(1);

  askToDelete(network: Network): void {
    // Two confirmations cannot be up at once: `shared/confirm-dialog` gives its phrase field a fixed
    // id, and two of them would be two labels pointing at one input.
    this.pendingSetDelete.set(null);
    this.pendingDuplicate.set(null);
    this.pendingRename.set(null);
    this.pendingDelete.set(network);
  }

  cancelDelete(): void {
    this.pendingDelete.set(null);
  }

  confirmDelete(): void {
    const network = this.pendingDelete();
    if (!network || this.store.deleting() !== null) {
      return;
    }
    this.store.delete(network.id).subscribe({
      next: () => this.pendingDelete.set(null),
      // The dialog stays open on failure. The reason is in the page's error banner, and closing would
      // leave the user guessing whether the deletion happened.
      error: () => undefined,
    });
  }

  // ------------------------------------------------ duplicating one network (FR-26)

  /** The network the duplicate dialog is open for, or null. */
  readonly pendingDuplicate = signal<Network | null>(null);

  /**
   * The name the copy will take. **Prefilled with the base network's**, and that is the feature.
   *
   * The prefill is not a placeholder but the intended way to take the next
   * version number and sort beside it: the server takes `findMaxVersion(name) + 1`, and
   * `sortNetworks` puts variants of one name together in version order, so an untouched field
   * produces the copy directly beneath its base. `forkRequestFrom` is what turns an untouched field
   * back into an omitted `name`, which is also what the editor's empty-with-a-placeholder field
   * resolves to - one rule, two presentations.
   */
  readonly duplicateName = signal('');

  /** The lever note - the same one the editor's fork prompt collects, and optional. */
  readonly duplicateNote = signal('');

  /**
   * The dialog's wording, from the pure module, or null while it is closed.
   *
   * Null rather than the `EMPTY_SPLIT` trick the set-delete confirmation uses: that one folds to an
   * unreachable object because its dialog also renders a *projected* list built from a second
   * signal, and two `@if`s over one dialog would be worse than one unreachable object. This dialog
   * has exactly one gate, so it can be the gate.
   */
  readonly duplicatePrompt = computed(() => {
    const base = this.pendingDuplicate();
    return base ? duplicateConfirm(base) : null;
  });

  /** What to say once the copy exists - a sentence above the table, never a navigation (FR-26). */
  readonly duplicateOutcome = duplicateOutcome;

  /**
   * The row the last duplicate created, or null - so the table can point at it (FR-26).
   *
   * FR-26 asks that the new network be identifiable, and the thing that makes that hard is exactly
   * what makes the duplicate correct: it shares its base's name and differs by a version number, in
   * a table where several rows already do. A mark on the row is the cheapest honest answer; the
   * alternative - navigating into the copy - is what FR-26 rules out.
   */
  readonly duplicatedId = computed(() => this.store.duplicated()?.copy.id ?? null);

  /**
   * Opens the duplicate dialog. **Gated on nothing** - a frozen network duplicates like any other.
   *
   * The freeze covers *edits*, and reading a configuration to copy it is not one; forking is the
   * remedy the freeze itself advertises. The menu entry is ungated for the same reason its three
   * exports are, and this is where that would have been quietly undone if it were going to be.
   */
  askToDuplicate(network: Network): void {
    // One confirmation at a time - `shared/confirm-dialog` gives its phrase field a fixed id, and
    // two dialogs would share one backdrop.
    this.pendingDelete.set(null);
    this.pendingSetDelete.set(null);
    this.pendingExport.set(null);
    this.pendingRename.set(null);
    this.duplicateName.set(network.name);
    this.duplicateNote.set('');
    this.pendingDuplicate.set(network);
  }

  cancelDuplicate(): void {
    this.pendingDuplicate.set(null);
  }

  /**
   * Forks the network (FR-26).
   *
   * The two fields are resolved by `core/fork-request.forkRequestFrom`, shared with the editor's
   * fork prompt: an untouched name means "the same name, next version" whether it arrived as a
   * prefilled value here or as a placeholder there.
   *
   * The dialog closes when the request settles, whichever way it went - the judgement the FR-24
   * export dialog makes and for its reason: a failure is a red banner at the top of the page, and a
   * modal left sitting over it would hide the sentence explaining what happened. Nothing navigates:
   * the copy appears in the table and in the lineage beneath it, and the researcher is left where
   * they were working.
   */
  confirmDuplicate(): void {
    const base = this.pendingDuplicate();
    if (!base || this.store.duplicating() !== null) {
      return;
    }
    this.store
      .duplicate(base, forkRequestFrom(this.duplicateName(), base.name, this.duplicateNote()))
      .subscribe({
        next: () => this.pendingDuplicate.set(null),
        error: () => this.pendingDuplicate.set(null),
      });
  }

  // ------------------------------------------------ renaming one network (FR-29)

  /** The network the rename dialog is open for, or null. */
  readonly pendingRename = signal<Network | null>(null);

  /**
   * The name being typed. **Prefilled with the current one**, so the field opens showing what it is
   * changing rather than an empty box the reader has to remember the contents of.
   *
   * Unlike the duplicate dialog's identically prefilled field, an untouched value here means
   * *nothing to do* rather than *the same name, next version*: `renameBlocker` reports it and the
   * action stays unavailable. The two dialogs prefill for the same ergonomic reason and resolve to
   * opposite requests, which is why neither shares the other's resolver.
   */
  readonly renameName = signal('');

  /** The dialog's wording, from the pure module, or null while it is closed. */
  readonly renamePrompt = computed(() => {
    const network = this.pendingRename();
    return network ? renameConfirm(network) : null;
  });

  /**
   * Why the rename cannot be sent yet, or null - the server's own validation, restated.
   *
   * Read live rather than snapshotted, because it is a statement about what is in the field right
   * now. Rendered under the field in both of its forms: red against the input when the name would
   * be refused, muted when it is merely the name the network already has. A disabled button with no
   * reason beside it is objectionable on the frozen row, and it would be the same
   * objection here.
   */
  readonly renameBlock = computed(() => {
    const network = this.pendingRename();
    return network ? renameBlocker(network, this.renameName()) : null;
  });

  /** What to say once the row has come back renamed - a sentence above the table (FR-29). */
  readonly renameOutcome = renameOutcome;

  /** The remedy under a refusal, keyed off its RFC 7807 code rather than its sentence. */
  readonly renameRefusalNote = renameRefusalNote;

  /**
   * The row the last rename changed, or null - so the table can point at it.
   *
   * The same problem the *New copy* badge of FR-26 solves, arrived at from the other direction: a
   * duplicate is hard to find because it shares its base's name, and a rename is hard to find
   * because the row **moved** when the table re-sorted around its new name.
   */
  readonly renamedId = computed(() => this.store.renamed()?.after.id ?? null);

  /**
   * Opens the rename dialog. **Gated on nothing** - a frozen network renames like any other.
   *
   * The freeze covers what a result was computed *from*: nodes, links, per-product
   * rows, the time base. A name is none of those, which is why the backend's guard came off
   * `NetworkService.update` and off nothing else, and this is where that would have been quietly
   * undone if it were going to be - the same sentence `askToDuplicate` carries, for the same reason.
   */
  askToRename(network: Network): void {
    // One confirmation at a time - `shared/confirm-dialog` gives its phrase field a fixed id, and
    // two dialogs would share one backdrop.
    this.pendingDelete.set(null);
    this.pendingSetDelete.set(null);
    this.pendingDuplicate.set(null);
    this.pendingExport.set(null);
    // A refusal describes the attempt that produced it. Opening the dialog fresh must not open it
    // showing the last one's.
    this.store.clearRenameFailure();
    this.renameName.set(network.name);
    this.pendingRename.set(network);
  }

  cancelRename(): void {
    this.pendingRename.set(null);
    this.store.clearRenameFailure();
  }

  /**
   * Renames the network (FR-29).
   *
   * The body is `network-rename.renameRequest`'s and is built inside `NetworksStore.rename`: name
   * **and** baseline flag, the flag taken from the row rather than from anything on this dialog,
   * because `PUT /networks/{id}` replaces both and a body carrying only the name would clear it.
   *
   * **The dialog stays open on a refusal**, which is the single-network delete's judgement rather
   * than the duplicate's and export's. Those two have nowhere useful to put the reader afterwards;
   * this one has the field that caused the refusal still filled in, so the message goes beside it
   * and the name can be corrected without retyping. It is also why the store writes
   * `renameFailure` rather than the page-level `_error`, which would have been hidden behind the
   * modal it is explaining.
   */
  confirmRename(): void {
    const network = this.pendingRename();
    if (!network || this.store.renaming() !== null || this.renameBlock() !== null) {
      return;
    }
    this.store.rename(network, this.renameName()).subscribe({
      next: () => this.pendingRename.set(null),
      error: () => undefined,
    });
  }

  // -------------------------------------------------- selecting a set, and acting on it (FR-23)

  /** The ticked networks sorted into what FR-15 permits and what it refuses. */
  readonly selectionSplit = computed(() => splitSelection(this.store.selectedNetworks()));

  /**
   * The split the set-delete confirmation is asking about, or null when it is closed.
   *
   * A **snapshot**, taken when the dialog opens, rather than `selectionSplit()` read live. The
   * deletes remove each network from the list as it is accepted (FR-23), which reconciles it out of
   * the selection - so a live reading would empty the dialog's own "these will be deleted" list
   * while it was deleting them, and count down to *Delete 0 networks* under the reader's hands. The
   * question a confirmation asks has to stay the question it was answered with.
   */
  readonly pendingSetDelete = signal<SelectionSplit | null>(null);

  /**
   * Why a set delete was not offered at all, or null.
   *
   * Every ticked network is frozen, so there is nothing to confirm and no request would be issued.
   * A banner rather than a dialog, which is the judgement `core/run-discard.activeRunsBlocker`
   * makes for a discard the server would refuse whole: asking someone to type a project name for a
   * guaranteed no-op is friction that buys nothing.
   */
  readonly selectionBlocker = signal<string | null>(null);

  /**
   * The wording of the set-delete confirmation, from the pure module (FR-23).
   *
   * The split is the frozen one; the phrase is read live, because it is the project's name and the
   * dialog must ask for whatever the header is currently showing.
   */
  readonly setDeleteConfirm = computed(() =>
    selectionDeleteConfirm(this.deletePhrase(), this.pendingSetDelete() ?? EMPTY_SPLIT),
  );

  /** `Baseline v2 (#7)` - used by both lists in the dialog and by the report. */
  readonly describeNetwork = describeNetwork;
  /** The remedy line under a refusal, keyed off its RFC 7807 code rather than its sentence. */
  readonly outcomeNote = outcomeNote;
  /** What the set delete did, in one sentence - what went *and* what did not. */
  readonly deletionHeadline = deletionHeadline;

  toggleNetwork(network: Network, selected: boolean): void {
    this.store.setSelected(network.id, selected);
  }

  toggleAllNetworks(selected: boolean): void {
    this.store.toggleAll(selected);
  }

  /** How many of the ticked networks were actually deleted - the report's headline count. */
  deletedCount(report: readonly DeletionOutcome[]): number {
    return report.filter((outcome) => outcome.status === 'deleted').length;
  }

  askToDeleteSelection(): void {
    const split = this.selectionSplit();
    const blocker = frozenSelectionBlocker(split);
    if (blocker) {
      this.selectionBlocker.set(blocker);
      return;
    }
    if (!split.deletable.length) {
      return;
    }
    this.selectionBlocker.set(null);
    this.pendingDelete.set(null);
    this.pendingDuplicate.set(null);
    this.pendingRename.set(null);
    this.pendingSetDelete.set(split);
  }

  cancelSetDelete(): void {
    this.pendingSetDelete.set(null);
  }

  /**
   * Issues the deletes, one network at a time, and lets the report speak (FR-23).
   *
   * What is sent is the intersection of the split the user was shown with what is *still* selected:
   * the dialog is what they agreed to, and the live selection is what is still there to act on. In
   * practice the two are the same - the modal owns the page while it is up and nothing here polls -
   * but the intersection is what makes "an action can never be aimed at a row that has gone" hold
   * without depending on that.
   *
   * The dialog closes when the sequence ends, whatever it did. The per-network report above the
   * table is where the outcome is read, and it is already accurate. Deliberately *not* kept open on
   * a refusal the way the single-network dialog is: there, one failure is the whole answer and the
   * dialog is the only place to say it; here the answer is a list, and a modal sitting over it would
   * hide the thing it is reporting.
   */
  confirmSetDelete(): void {
    const asked = this.pendingSetDelete();
    if (!asked || this.store.deletingMany() !== null) {
      return;
    }
    const stillSelected = new Set(this.store.selectedNetworks().map((network) => network.id));
    const going = asked.deletable.filter((network) => stillSelected.has(network.id));
    if (!going.length) {
      this.pendingSetDelete.set(null);
      return;
    }
    this.store.deleteMany(going).subscribe({
      next: () => this.pendingSetDelete.set(null),
      error: () => this.pendingSetDelete.set(null),
    });
  }

  // ------------------------------------------- exporting a selection as a project (FR-24)

  /**
   * The selection the export confirmation is asking about, or null when it is closed.
   *
   * A **snapshot**, taken when the dialog opens, for the reason {@link pendingSetDelete} is one -
   * except that here the risk runs the other way. Nothing about an export changes the list, so the
   * live selection would in practice hold still; what a snapshot buys is that the request is issued
   * for the networks the dialog *named*, so the file cannot quietly disagree with the list the user
   * read and approved. A background reload that reconciled a row away between the two would
   * otherwise produce an archive one network short of what was confirmed, with nothing on screen
   * having said so.
   */
  readonly pendingExport = signal<readonly Network[] | null>(null);

  /** The wording of the export confirmation, from the pure module (FR-24). */
  readonly exportConfirm = computed(() => selectionExportConfirm(this.pendingExport() ?? []));

  askToExportSelection(): void {
    const selected = this.store.selectedNetworks();
    if (!selected.length) {
      return;
    }
    // Two confirmations cannot be up at once: `shared/confirm-dialog` gives its phrase field a
    // fixed id, and this one has no phrase but shares the backdrop.
    this.pendingDelete.set(null);
    this.pendingSetDelete.set(null);
    this.pendingDuplicate.set(null);
    this.pendingRename.set(null);
    this.pendingExport.set(selected);
  }

  cancelExport(): void {
    this.pendingExport.set(null);
  }

  /**
   * Downloads the selection as a standalone project archive (FR-24).
   *
   * Straight through `ProjectArchiveService`, the same service the whole-project card uses - the
   * subset is that request with `networkIds`, and a second download path would be a second place to
   * get the bearer token, the blob and the `Content-Disposition` filename right. The server names a
   * subset file `<project>-3-networks-archive.zip`, which is precisely the sort of thing a second
   * implementation would have gone on guessing.
   *
   * The dialog closes when the request settles, whichever way it went: a failure is a red banner at
   * the top of the page, in the same place the whole-project export's failures appear, and a modal
   * left sitting over it would hide the sentence explaining what happened.
   */
  confirmExport(): void {
    const projectId = this.projectId();
    const selected = this.pendingExport();
    if (!projectId || !selected?.length || this.archives.busyFor() !== null) {
      return;
    }
    void this.archives
      .download(
        projectId,
        selected.map((network) => network.id),
      )
      .finally(() => this.pendingExport.set(null));
  }

  // ------------------------------------- the selection, side by side in a new window (FR-25)

  /**
   * The side-by-side URL when the browser refused to open it, or null.
   *
   * A pop-up blocker returns null from `window.open` and says nothing else, so the address is put on
   * screen as a link the reader can take themselves. That is only possible *because* the view's whole
   * state is in its URL (FR-25): a window opened by handing data to a child would have nothing to
   * offer here but an apology.
   */
  readonly blockedComparison = signal<string | null>(null);

  /**
   * Open the ticked networks side by side, in a **new browser window** (FR-25).
   *
   * > "The Compare action opens a new browser window showing one pane per selected network."
   *
   * The address is serialised by the router rather than assembled by hand - one place that knows
   * this application's URL shape - and the ids travel on the query string, which is what makes the
   * window a place rather than a handoff: reload it, bookmark it, send it to a supervisor, and it is
   * the same twelve panes. Nothing is passed in memory, so nothing is lost when the opener is closed.
   *
   * `window.open` is called **synchronously in the click**, which is the condition every browser
   * attaches to allowing a pop-up at all; a confirmation dialog in between would have made this a
   * blocked window every time. There is nothing to confirm in any case - the view is read-only, so
   * there is no consequence to warn about, which is exactly the argument `selectionExportConfirm`
   * makes for having no typed phrase and then goes on to need a dialog for reasons that do not apply
   * here (nothing is written, and no file exists afterwards to be wrong).
   *
   * The cap is `compareBlocker`'s, the same function the menu states it from, so the entry cannot
   * offer what this refuses.
   */
  openComparison(): void {
    const projectId = this.projectId();
    const selected = this.store.selectedNetworks();
    if (!projectId || compareBlocker(selected.length) !== null) {
      return;
    }
    this.blockedComparison.set(null);
    const url = this.router.serializeUrl(
      this.router.createUrlTree(['/projects', projectId, 'comparison', 'structure'], {
        queryParams: { ids: paneIdsParam(selected.map((network) => network.id)) },
      }),
    );
    // A relative URL resolves against this document, so the new window lands on the same origin and
    // the same base href without either being restated here.
    //
    // `noopener` and **no size features**, deliberately. Naming a width or a height would make it a
    // pop-up in Chrome's sense - a window with no address bar - which would take away the one thing
    // FR-25 asks the view to be: an address the reader can see, copy and bookmark. Whether the
    // browser gives them a tab or a window is theirs to configure, and either is side by side.
    // `noopener` severs `window.opener` so the new context is genuinely independent, which is the
    // same fact as the ids being in the URL rather than in this page's memory.
    const opened = window.open(url, '_blank', 'noopener');
    if (!opened) {
      this.blockedComparison.set(url);
    }
  }

  private loadProject(projectId: Id): void {
    this.projectError.set(null);
    this.projects.get(projectId).subscribe({
      next: (project) => this.project.set(project),
      error: (failure: unknown) => {
        this.project.set(null);
        this.projectError.set(problemMessage(failure, 'Could not load this project.'));
      },
    });
  }
}
