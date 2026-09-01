import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { Id, Product, Project } from '../../../core/models';
import { problemMessage } from '../../../core/problem-details';
import { ConfirmDialogComponent } from '../../../shared/confirm-dialog/confirm-dialog.component';
import { ProductUsageService } from '../product-usage.service';
import { ProductsStore } from '../products.store';
import { ProjectsStore } from '../projects.store';

/** How many node names one network contributes to the delete dialog before it says "and N more". */
const NAMES_SHOWN = 10;

/**
 * The product catalogue of one project, with create, edit and delete (FR-01).
 *
 * ## Why this screen exists
 *
 * Demand lives on a `node_product` row, and a `node_product` row needs a product. Until
 * this screen existed the only way to put a product in a project was to import one, so a project
 * started from scratch had an empty catalogue, no node in it could be given demand, and
 * `POST /simulations` refused the network outright with `NETWORK_HAS_NO_DEMAND`. The
 * catalogue is the first link in that chain, which is why the empty state here spells the chain out
 * rather than just saying the table is empty.
 *
 * ## Why it is on the project and not in the editor
 *
 * A product is project-scoped (`PRODUCT`). Every configuration variant of a project is
 * a different structure over one catalogue - that shared catalogue is what makes two variants' cost
 * metrics comparable in the comparison view. A catalogue screen inside the editor would
 * read as belonging to the network open in it. The property panel still offers a *shortcut* to
 * create one without leaving the canvas; it does not own the catalogue.
 *
 * ## Editing is name and unit value together
 *
 * `PUT /products/{id}` is a full replacement and `unitValue` is a primitive on the backend, so a
 * rename that sent only the name would silently zero the unit value - and with it every monetary
 * metric that weights unserved demand by it. The row therefore edits both, which is also
 * the only way to correct a unit value that was typed wrong.
 *
 * ## Deleting asks first, because the server will not
 *
 * `DELETE /products/{id}` **cascades**. Its `node_product` rows go across every network of the
 * project, frozen ones included. There is no 409 to catch and nothing to undo, so the dialog sweeps
 * the project first and names the nodes that will lose their figures ({@link ProductUsageService}),
 * and asks for the product's name to be typed whenever the answer is anything other than a
 * confirmed "nothing carries it".
 *
 * The typed phrase is the **product's** name here, unlike the network deletion which asks
 * for the *project's*. A network shares its name with every variant of it, so its own name would not
 * say which one is going; a product's name is unique within its project and names it exactly.
 *
 * The dialog is *not* held shut while the sweep runs. `busy` on the confirm dialog disables cancel
 * and the phrase field as well as the confirm button, so binding it to the sweep would trap the user
 * behind a hundred requests they did not ask for. The guard in that window is the phrase itself,
 * which is required until the sweep has come back clean.
 */
@Component({
  selector: 'app-product-catalogue',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, DatePipe, ConfirmDialogComponent],
  templateUrl: './product-catalogue.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductCatalogueComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly projects = inject(ProjectsStore);
  private readonly usageService = inject(ProductUsageService);
  readonly store = inject(ProductsStore);

  readonly projectId = signal<Id | null>(null);
  readonly project = signal<Project | null>(null);
  readonly projectError = signal<string | null>(null);

  /** 120 characters mirrors `product.name VARCHAR(120)`, so an over-long name never reaches the API. */
  readonly newProduct = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(120)],
    }),
    unitValue: new FormControl(0, {
      nonNullable: true,
      validators: [Validators.required, Validators.min(0)],
    }),
  });

  /**
   * The in-place edit, as two bare controls rather than a `FormGroup`.
   *
   * A group would need a `<form [formGroup]>` around both inputs, and they sit in different cells of
   * the same table row - the wrapper would have to straddle the cells. Two `[formControl]` bindings
   * keep the row a row, the way the project list's rename does.
   */
  readonly editName = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.maxLength(120)],
  });

  readonly editUnitValue = new FormControl(0, {
    nonNullable: true,
    validators: [Validators.required, Validators.min(0)],
  });

  /** Which row is in edit mode; null when none is. */
  readonly editingId = signal<Id | null>(null);

  constructor() {
    // Route params rather than ngOnInit: navigating between two projects reuses this component.
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const id = Number(params.get('projectId'));
      if (!Number.isFinite(id) || id <= 0) {
        this.projectError.set('That project id is not valid.');
        return;
      }
      this.projectId.set(id);
      this.loadProject(id);
      this.store.load(id);
    });
  }

  /** Back to the networks of this project. */
  dashboardLink(): (string | number)[] {
    return ['/projects', this.projectId() ?? 0];
  }

  create(): void {
    const projectId = this.projectId();
    if (!projectId || this.newProduct.invalid || this.store.creating()) {
      this.newProduct.markAllAsTouched();
      return;
    }
    const { name, unitValue } = this.newProduct.getRawValue();
    // `required` has already rejected an empty box; the coercion is for the type, not the value.
    this.store.create(projectId, { name, unitValue: Number(unitValue) }).subscribe({
      next: () => this.newProduct.reset({ name: '', unitValue: 0 }),
      // The store already turned the failure into a message; nothing further to do here.
      error: () => undefined,
    });
  }

  // ---------------------------------------------------------------------- editing

  startEdit(product: Product): void {
    this.editingId.set(product.id);
    this.editName.setValue(product.name);
    this.editUnitValue.setValue(product.unitValue);
  }

  cancelEdit(): void {
    this.editingId.set(null);
  }

  saveEdit(product: Product): void {
    if (this.editName.invalid || this.editUnitValue.invalid || this.store.busyFor() !== null) {
      this.editName.markAsTouched();
      this.editUnitValue.markAsTouched();
      return;
    }
    const name = this.editName.value.trim();
    // The number input hands back a string when it is emptied, so coerce before comparing.
    const unitValue = Number(this.editUnitValue.value);
    if (!Number.isFinite(unitValue) || unitValue < 0) {
      this.editUnitValue.markAsTouched();
      return;
    }
    if (name === product.name && unitValue === product.unitValue) {
      this.editingId.set(null);
      return;
    }
    this.store.replace(product.id, { name, unitValue }).subscribe({
      next: () => this.editingId.set(null),
      error: () => undefined,
    });
  }

  // --------------------------------------------------------------------- deletion

  readonly pendingDelete = signal<Product | null>(null);

  readonly usage = this.usageService.usage;
  readonly usageScanning = this.usageService.scanning;

  /**
   * The phrase to type, or null to let a plain click do it.
   *
   * Null **only** when the sweep finished, finished completely, and found nothing: a product nobody
   * carries costs nothing to delete, and asking someone to type its name for that is friction with
   * no risk behind it. Every other state - still sweeping, incomplete, or in use - asks, because not
   * knowing what a delete destroys is not the same as knowing it destroys nothing.
   */
  readonly deletePhrase = computed(() => {
    const product = this.pendingDelete();
    if (!product) {
      return null;
    }
    return this.usageService.knownUnused() ? null : product.name;
  });

  /** What the delete takes with it, as far as the sweep has managed to establish. */
  readonly deleteDetails = computed<readonly string[]>(() => {
    const standing = [
      'Its demand, initial inventory, safety stock and holding cost go with it - on every node of every network in this project.',
      'A product is project-scoped, so this is the one delete that reaches past a network frozen by a simulation run.',
      'Nodes, links and the networks themselves are untouched.',
    ];

    if (this.usageScanning()) {
      return [...standing, 'Checking which nodes carry it…'];
    }

    const usage = this.usage();
    if (!usage) {
      return [
        ...standing,
        'Could not check which nodes carry it. Treat the deletion as though every node does.',
      ];
    }

    if (!usage.networks.length) {
      return [
        ...standing,
        usage.partial
          ? 'No node found carrying it - but the check was incomplete, so this is not a guarantee.'
          : 'No node in this project carries it, so nothing is lost but the catalogue entry.',
      ];
    }

    const lines = usage.networks.map((entry) => {
      const shown = entry.nodeNames.slice(0, NAMES_SHOWN).join(', ');
      const rest = entry.nodeNames.length - NAMES_SHOWN;
      const overflow = rest > 0 ? `, and ${rest} more` : '';
      const frozen = entry.editable ? '' : ' - frozen by a run';
      return `${entry.label}${frozen}: ${shown}${overflow}`;
    });

    const total =
      `${usage.nodeCount} node${usage.nodeCount === 1 ? '' : 's'} across ` +
      `${usage.networks.length} network${usage.networks.length === 1 ? '' : 's'} carry it` +
      (usage.frozenCount ? `, ${usage.frozenCount} of them frozen.` : '.');

    return usage.partial
      ? [...standing, `${total} The check was incomplete, so there may be more.`, ...lines]
      : [...standing, total, ...lines];
  });

  askToDelete(product: Product): void {
    this.pendingDelete.set(product);
    const projectId = this.projectId();
    if (projectId) {
      void this.usageService.scan(projectId, product.id);
    }
  }

  cancelDelete(): void {
    this.pendingDelete.set(null);
    this.usageService.reset();
  }

  confirmDelete(): void {
    const product = this.pendingDelete();
    if (!product || this.store.busyFor() !== null) {
      return;
    }
    this.store.delete(product.id).subscribe({
      next: () => this.cancelDelete(),
      // The dialog stays open on failure. The reason is in the page's error banner, and closing it
      // would leave the user guessing whether the deletion happened.
      error: () => undefined,
    });
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
