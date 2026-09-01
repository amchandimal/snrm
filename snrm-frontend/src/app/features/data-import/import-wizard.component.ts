import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  computed,
  effect,
  inject,
  input,
  numberAttribute,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { Id } from '../../core/models';
import { MappingStepComponent } from './mapping-step/mapping-step.component';
import { DataImportStore, ImportStep } from './data-import.store';
import { ReportStepComponent } from './report-step/report-step.component';
import { RolesStepComponent } from './roles-step/roles-step.component';
import { UploadStepComponent } from './upload-step/upload-step.component';

/**
 * The data-import wizard (FR-02, FR-28).
 *
 * > "**Data import wizard** - three steps: upload CSV files or an Excel workbook → map columns to the
 * > canonical schema → review the validation report and confirm. The imported network
 * > opens in the editor."
 *
 * This component owns the frame - the stepper, the error banner, the navigation between steps - and
 * nothing else. Each step is its own component reading {@link DataImportStore}, and the step on screen
 * is derived from what the store has fetched rather than tracked here (see that class).
 *
 * On a committed import it navigates straight into the editor, which is the last clause and
 * the point of the whole flow: an imported network is a starting point for modelling, not a filing
 * action. The editor's auto-layout is what makes that land well - an import carries no canvas
 * coordinates, so `pos_x`/`pos_y` are null and the layered auto-layout arranges the graph on arrival.
 *
 * **FR-28 adds a fourth step and only for a batch.** When the upload is several workbooks the wizard
 * gains the roles step between mapping and the report, and the frame says so - the title,
 * the sentence under it and the stepper all follow `DataImportStore.batch()`. With one network being
 * imported every one of them renders exactly what it rendered before, which is the point: this is an
 * added path, not a replacement. Nor does a batch navigate anywhere - it creates several networks, so
 * there is no single editor to open and the report step ends at the project instead.
 */
@Component({
  selector: 'app-import-wizard',
  standalone: true,
  imports: [
    RouterLink,
    UploadStepComponent,
    MappingStepComponent,
    RolesStepComponent,
    ReportStepComponent,
  ],
  templateUrl: './import-wizard.component.html',
  styleUrl: './import-wizard.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportWizardComponent implements OnDestroy {
  readonly store = inject(DataImportStore);
  private readonly router = inject(Router);

  /**
   * From the route, via `withComponentInputBinding` - the project scopes the network and its
   * product catalogue.
   */
  readonly projectId = input.required({ transform: numberAttribute });

  /** Three steps for one network, four for a batch - see {@link DataImportStore.steps}. */
  readonly steps = this.store.steps;
  readonly Step = ImportStep;

  readonly stepIndex = computed(() => this.steps().indexOf(this.store.step()));

  readonly stepLabels: Readonly<Record<ImportStep, string>> = {
    UPLOAD: 'Upload',
    MAPPING: 'Map columns',
    ROLES: 'Roles',
    REPORT: 'Validate & confirm',
  };

  /**
   * The last step's label.
   *
   * A batch has already committed by the time it is on screen, one transaction per file, so
   * "Validate & confirm" would name a decision the reader is not being offered (FR-28).
   */
  stepLabel(step: ImportStep): string {
    if (step === ImportStep.REPORT && this.store.batch()) {
      return 'Import & report';
    }
    return this.stepLabels[step];
  }

  constructor() {
    // The wizard is entered fresh every time: a half-finished import from a previous visit would hold
    // File objects the user has forgotten choosing.
    effect(
      () => {
        const projectId = this.projectId();
        if (Number.isFinite(projectId) && projectId > 0 && this.store.projectId() !== projectId) {
          this.store.start(projectId);
        }
      },
      { allowSignalWrites: true },
    );
  }

  ngOnDestroy(): void {
    // Files are not serialisable and must not outlive the wizard.
    this.store.reset();
  }

  stepState(step: ImportStep): 'done' | 'current' | 'todo' {
    const index = this.steps().indexOf(step);
    if (index < this.stepIndex()) {
      return 'done';
    }
    return index === this.stepIndex() ? 'current' : 'todo';
  }

  /** The imported network opens in the editor. */
  async onImported(networkId: Id): Promise<void> {
    await this.router.navigate(['/projects', this.projectId(), 'networks', networkId, 'editor']);
  }
}
