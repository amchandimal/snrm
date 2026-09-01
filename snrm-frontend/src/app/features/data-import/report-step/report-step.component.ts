import { ChangeDetectionStrategy, Component, computed, inject, output } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Id } from '../../../core/models';
import { BatchRole, BatchStatus } from '../batch-plan';
import { BatchOutcome, DataImportStore } from '../data-import.store';
import { ImportReportViewComponent } from './import-report-view.component';

/**
 * The last step of the wizard: the two-stage validation report and the confirm for one network, or
 * the **per-file report** of a batch (FR-28).
 *
 * > "review the validation report and confirm."
 *
 * ## One network: unchanged
 *
 * A dry run has been shown - `validateOnly=true`, so nothing exists yet - and confirming re-posts the
 * same files and mapping with it false. An import is transactional and all-or-nothing, so
 * the user is confirming a decision, and confirming something they have not been shown is exactly
 * what the dry run prevents. The report itself is drawn by {@link ImportReportViewComponent}, which
 * is this component's own markup extracted so that a batch can draw it once per file; the verdict
 * above it is projected into that component, because the verdict is the one part that differs.
 *
 * ## A batch: what happened, per file, as it happens
 *
 * A batch turns this step into a per-file report: each file with its own two-stage findings and its
 * own outcome - created (with its new network's name and version), refused (with its rows), or not
 * attempted, because the batch stops at nothing and reports what happened to each.
 *
 * **The verdict at the top is a third thing, deliberately.** "A batch that created some networks and
 * refused others says so at the top rather than reading as either a success or a failure" - so
 * `batch-plan.batchSummary` has a `PARTIAL` verdict of its own, and the alert here renders it amber
 * rather than picking one of the other two colours. Green would hide the file that has to be fixed;
 * red would suggest the ones that worked have to be imported again, which is the lottery the
 * per-file transaction exists to avoid.
 *
 * **There is no Confirm, and nothing navigates.** A batch has already committed, one transaction per
 * file, so there is nothing left to agree to; and it created several networks, so there is no single
 * editor to open. Each created row offers its own editor link and the step ends at the project.
 * *Back to roles* is offered only while nothing was created, because re-running a batch that made
 * networks would make them again under the next version number rather than replacing them.
 */
@Component({
  selector: 'app-report-step',
  standalone: true,
  imports: [RouterLink, ImportReportViewComponent],
  templateUrl: './report-step.component.html',
  styleUrl: './report-step.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReportStepComponent {
  readonly store = inject(DataImportStore);

  /** Emitted once a network exists, so the wizard can open it in the editor. */
  readonly imported = output<Id>();

  readonly report = this.store.report;

  // ------------------------------------------------------------------ batch (FR-28)

  readonly Status = BatchStatus;
  readonly Role = BatchRole;

  readonly outcomes = this.store.batchOutcomes;
  readonly summary = this.store.batchSummary;

  /** Nothing exists yet, so the roles step can be returned to and the batch re-planned. */
  readonly canGoBackToRoles = computed(
    () => !this.store.busy() && this.store.batchCreated().length === 0,
  );

  /** `Created as Baseline v2 (#7)` - the name and version each created row states. */
  createdLabel(outcome: BatchOutcome): string {
    const network = outcome.network;
    if (!network) {
      // The commit succeeded but the response carried no network body. The id still identifies it.
      return `Created as #${outcome.report?.networkId ?? '?'}`;
    }
    return `Created as ${network.name} v${network.version} (#${network.id})`;
  }

  /** What the row's role means once the file has been imported. */
  roleLabel(outcome: BatchOutcome): string {
    switch (outcome.role) {
      case BatchRole.BASELINE:
        return 'Baseline of this batch';
      case BatchRole.VARIANT:
        return 'Configuration variant of the baseline';
      default:
        return 'Independent network';
    }
  }

  statusLabel(status: BatchStatus): string {
    switch (status) {
      case BatchStatus.CREATED:
        return 'Created';
      case BatchStatus.REFUSED:
        return 'Refused';
      case BatchStatus.NOT_ATTEMPTED:
        return 'Not attempted';
      default:
        return 'Waiting';
    }
  }

  async confirm(): Promise<void> {
    const committed = await this.store.confirm();
    if (committed?.networkId) {
      this.imported.emit(committed.networkId);
    }
  }
}
