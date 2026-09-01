import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ConfirmDialogComponent } from '../../../shared/confirm-dialog/confirm-dialog.component';
import { Id, Project } from '../../../core/models';
import { ArchiveImportComponent } from '../archive-import/archive-import.component';
import { ArchiveRestoreStore } from '../archive-restore.store';
import { ProjectsStore } from '../projects.store';

/**
 * Project list with create, rename and delete (`features/projects`, FR-01).
 *
 * The entry point of the application: everything else in SNRM hangs off a project.
 * Renaming happens in place rather than in a dialog - one field, and the row is already the context.
 *
 * ## The archive restore lives here
 *
 * Export is a control on a project's dashboard; the matching import is on this screen, because a
 * restore **creates a new project** rather than adding to one - there is no project to host it on
 * until it has run. `ArchiveImportComponent` is the panel; this component's only part in it is
 * reloading the list when a restore succeeds, so the new project appears in the table beside the
 * report that describes it.
 */
@Component({
  selector: 'app-project-list',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    ConfirmDialogComponent,
    ArchiveImportComponent,
  ],
  templateUrl: './project-list.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProjectListComponent implements OnInit, OnDestroy {
  readonly store = inject(ProjectsStore);
  private readonly archives = inject(ArchiveRestoreStore);

  /**
   * The create form.
   *
   * A `FormGroup` rather than a bare `FormControl` because the `<form>` needs `[formGroup]` on it:
   * `ngSubmit` is an output of `FormGroupDirective`, and without that directive the browser submits
   * the form natively and reloads the page instead of calling {@link create}.
   *
   * 160 characters mirrors `project.name VARCHAR(160)`, so an over-long name never reaches the API.
   */
  readonly newProject = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(160)],
    }),
  });

  readonly renameControl = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.maxLength(160)],
  });

  /** Which row is in rename mode; null when none is. */
  readonly renamingId = signal<Id | null>(null);

  /** The project the confirm dialog is asking about. */
  readonly pendingDelete = signal<Project | null>(null);

  /**
   * What a delete takes with it, spelled out in the dialog.
   *
   * The API cascades along the ownership edges of the schema, and a researcher deleting a
   * project a week before submission should be told that before confirming, not after.
   */
  readonly deleteDetails: readonly string[] = [
    'Everything in the project goes with it: networks, nodes, links, products, scenarios, simulation runs, metric results and time series.',
    'Completed simulation runs do not block this, unlike editing a network.',
  ];

  ngOnInit(): void {
    this.store.load();
  }

  /**
   * Drops the chosen archive and its report on the way out.
   *
   * `ArchiveRestoreStore` is `providedIn: 'root'` like every other store, but it holds a `File`,
   * which is not serialisable and must not outlive the page - the same rule `DataImportStore`
   * follows for the wizard's upload.
   */
  ngOnDestroy(): void {
    this.archives.reset();
  }

  /**
   * A restore created a project.
   *
   * The list is re-read rather than having the new row spliced in locally: the restore may have
   * renamed the project to avoid a collision (`PROJECT_RENAMED`), and the name in the table has to
   * be the one the server assigned rather than the one that was asked for.
   */
  onRestored(): void {
    this.store.load();
  }

  create(): void {
    if (this.newProject.invalid || this.store.pending() === 'new') {
      this.newProject.markAllAsTouched();
      return;
    }
    this.store.create(this.newProject.controls.name.value).subscribe({
      next: () => this.newProject.reset({ name: '' }),
      // The store already turned the failure into a message; nothing further to do here.
      error: () => undefined,
    });
  }

  startRename(project: Project): void {
    this.renamingId.set(project.id);
    this.renameControl.setValue(project.name);
  }

  cancelRename(): void {
    this.renamingId.set(null);
  }

  saveRename(project: Project): void {
    if (this.renameControl.invalid) {
      this.renameControl.markAsTouched();
      return;
    }
    if (this.renameControl.value.trim() === project.name) {
      this.renamingId.set(null);
      return;
    }
    this.store.rename(project.id, this.renameControl.value).subscribe({
      next: () => this.renamingId.set(null),
      error: () => undefined,
    });
  }

  askDelete(project: Project): void {
    this.pendingDelete.set(project);
  }

  confirmDelete(): void {
    const project = this.pendingDelete();
    if (!project) {
      return;
    }
    this.store.remove(project.id).subscribe({
      next: () => this.pendingDelete.set(null),
      error: () => this.pendingDelete.set(null),
    });
  }
}
