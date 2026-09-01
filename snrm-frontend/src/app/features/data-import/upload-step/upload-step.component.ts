import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { FileDropComponent } from '../../../shared/file-drop/file-drop.component';
import { DataImportStore } from '../data-import.store';

/**
 * Step 1 of the import wizard: choose the files (FR-28).
 *
 * Thin on purpose. All it does is collect files and ask the server what they contain - no parsing, no
 * validation, no guessing which sheet a file is. Sheet identity comes from the filename for a CSV and
 * from the sheet names for a workbook, so there is nothing here for the user to label.
 *
 * **The one thing it now reads from a file name is the network's name** (FR-28). Several workbooks are
 * several networks, each named after its own file, and this step shows those names before anything is
 * posted - a batch of four is four decisions being taken from four file names, and the cheapest place
 * to notice that `Copy of Baseline (2).xlsx` is about to become a network name is here.
 *
 * **A mixed upload is refused here rather than by the server**, and the refusal states what the batch
 * form accepts. See `file-names.ts`: once a second workbook means a second network, a workbook beside
 * a `nodes.csv` has two readings and the wizard would have to guess between them. Nothing about the
 * three single-network shapes changes - one workbook, a CSV set, an XML document are read exactly as
 * they always were.
 */
@Component({
  selector: 'app-upload-step',
  standalone: true,
  imports: [FileDropComponent],
  templateUrl: './upload-step.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UploadStepComponent {
  readonly store = inject(DataImportStore);
}
