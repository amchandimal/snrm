import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { DisruptionScenario, Id, Project } from '../../../core/models';
import { problemMessage } from '../../../core/problem-details';
import { ConfirmDialogComponent } from '../../../shared/confirm-dialog/confirm-dialog.component';
import { ProjectsStore } from '../../projects/projects.store';
import { ScenariosStore } from '../scenarios.store';

/**
 * The disruption scenarios of one project, with create, duplicate and delete (FR-05).
 *
 * The way into the timeline: a row opens the builder for that scenario. It sits beside the project's
 * networks on the dashboard rather than under one of them, because a scenario belongs to the project
 * - the same disruption story is replayed against every configuration variant, which is what makes
 * the comparison view meaningful.
 *
 * **Duplicate is the primary action here**, more than create. A scenario is a story with a shape:
 * three events, particular severities, a recovery profile chosen for a reason. Exploring "the same
 * fire two weeks later" by retyping it is how two scenarios end up differing in a way nobody
 * intended, so the copy is deep, server-side, and one click.
 *
 * **Delete asks for the project's name.** Same reasoning as the network deletion: the
 * scenario goes with every event in it, there is nothing to fork back to, and the friction is sized
 * to that. The scenario's own name is on the row and would be typed without being read.
 */
@Component({
  selector: 'app-scenario-list',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, DatePipe, ConfirmDialogComponent],
  templateUrl: './scenario-list.component.html',
  styleUrl: './scenario-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScenarioListComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly projects = inject(ProjectsStore);
  readonly store = inject(ScenariosStore);

  readonly projectId = signal<Id | null>(null);
  readonly project = signal<Project | null>(null);
  readonly projectError = signal<string | null>(null);

  readonly newScenario = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(160)],
    }),
    numReplications: new FormControl(100, {
      nonNullable: true,
      validators: [Validators.required, Validators.min(1)],
    }),
  });

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

  create(): void {
    const projectId = this.projectId();
    if (!projectId || this.newScenario.invalid || this.store.creating()) {
      this.newScenario.markAllAsTouched();
      return;
    }
    const { name, numReplications } = this.newScenario.getRawValue();
    this.store.create(projectId, { name, numReplications }).subscribe({
      // Straight into the builder: a scenario with no events is not something to look at in a list.
      next: (scenario) => {
        this.newScenario.reset({ name: '', numReplications: 100 });
        void this.router.navigate(this.builderLink(scenario));
      },
      error: () => undefined,
    });
  }

  /** Route to the timeline for a scenario. */
  builderLink(scenario: DisruptionScenario): (string | number)[] {
    return ['/projects', scenario.projectId, 'scenarios', scenario.id];
  }

  duplicate(scenario: DisruptionScenario): void {
    if (this.store.busyFor() !== null) {
      return;
    }
    this.store.duplicate(scenario.id).subscribe({ error: () => undefined });
  }

  // ------------------------------------------------------------------- deletion

  readonly pendingDelete = signal<DisruptionScenario | null>(null);

  /** The owning project's name - see the class note on why not the scenario's. */
  readonly deletePhrase = computed(() => this.project()?.name ?? '');

  readonly deleteDetails: readonly string[] = [
    'Every event in the scenario goes with it.',
    'Networks, nodes and links are untouched - a scenario references them, it does not own them.',
    'A scenario a completed simulation run still refers to cannot be deleted while that run exists.',
  ];

  askToDelete(scenario: DisruptionScenario): void {
    this.pendingDelete.set(scenario);
  }

  cancelDelete(): void {
    this.pendingDelete.set(null);
  }

  confirmDelete(): void {
    const scenario = this.pendingDelete();
    if (!scenario || this.store.busyFor() !== null) {
      return;
    }
    this.store.delete(scenario.id).subscribe({
      next: () => this.pendingDelete.set(null),
      // The dialog stays open on failure; the reason is in the page banner, and closing would leave
      // the user guessing whether the deletion happened.
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
