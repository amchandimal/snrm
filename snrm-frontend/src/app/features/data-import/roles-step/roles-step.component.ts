import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { BatchRole } from '../batch-plan';
import { DataImportStore } from '../data-import.store';

/**
 * Step 3 of a batch import: which file is the baseline, and what each of the others is
 * (FR-28).
 *
 * > "a row per file, each showing the network name taken from its file name, one **baseline** choice
 * > across the batch, and for every other file a choice between *variant of the baseline* and
 * > *independent network*. The default is variant, because a folder of workbooks from one study is
 * > usually one configuration and its alternatives, and the reader who disagrees is one click from
 * > saying so."
 *
 * The step exists only for a batch and cannot be reached otherwise - the wizard's step is derived
 * from `DataImportStore.roles()` **and** from the upload being a batch, so "the roles step with
 * nothing to assign roles to" is unrepresentable rather than guarded against (see that store).
 *
 * ## There is no name field on this step, and that is the design
 *
 * A name comes from its file and is shown here so the researcher can see what four files
 * are about to be called - not so they can edit it. FR-29 is the other half of that decision: "a name
 * assigned from a file name is a label rather than a decision, so the table renames one in place",
 * and a rename there is not a structural edit and is not refused on a frozen network. A column of
 * text inputs here would be the same feature built twice, in the place where the researcher has least
 * information about what the network turned out to contain.
 *
 * ## Nothing checks the names against the project either
 *
 * A name already used in the project takes the next version number exactly as any other
 * network of that name would. So a clash is not a problem to warn about, it is the documented way to
 * add a variant - and a client-side check would either pre-empt the server's `findMaxVersion(name) + 1`
 * or duplicate it and disagree with it. The step says so instead.
 *
 * ## The two baselines
 *
 * The one question this step asks feeds two different things, and `batch-plan.ts` keeps them apart:
 * the **project's baseline flag** (at most one per project) and the **base of this batch's
 * variant edges**. Where the project already has a flag, the chosen file is only the edge base and
 * {@link DataImportStore.baselineNote} says so *here*, rather than silently failing at the fifth
 * file, because that failure lands on the baseline request and takes
 * every variant behind it with it.
 */
@Component({
  selector: 'app-roles-step',
  standalone: true,
  templateUrl: './roles-step.component.html',
  styleUrl: './roles-step.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RolesStepComponent {
  readonly store = inject(DataImportStore);

  readonly Role = BatchRole;

  readonly rows = this.store.rows;
  readonly plan = this.store.plan;

  /** How many networks the import will actually attempt - the number on the button. */
  readonly sendable = computed(() => this.plan()?.steps.length ?? 0);

  readonly unnamed = computed(() => this.plan()?.unnamed ?? []);

  onBaseline(index: number): void {
    this.store.setBaselineFile(index);
  }

  onRole(index: number, role: string): void {
    if (role === BatchRole.VARIANT || role === BatchRole.INDEPENDENT) {
      this.store.setRole(index, role);
    }
  }
}
