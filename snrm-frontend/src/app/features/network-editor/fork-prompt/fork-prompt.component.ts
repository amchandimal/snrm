import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

import { ForkRequest, forkRequestFrom } from '../../../core/fork-request';
import { Network } from '../../../core/models';

/**
 * The fork-to-variant prompt.
 *
 * > "If simulations have already run against the network, the first edit prompts the user to fork a
 * > new configuration variant […] rather than silently mutating history."
 *
 * Raised from two places: proactively, when `GET /networks/{id}` comes back with `editable: false`,
 * and reactively, when a write returns 409 `NETWORK_IMMUTABLE`. Both end here.
 *
 * The blocked edit is **not** carried over to the variant, and the dialog says so. Replaying an edit
 * across a fork would produce a configuration the researcher never explicitly chose, which is the
 * exact failure mode the immutability rule exists to prevent.
 *
 * ## Why the dialog asks what the variant is for
 *
 * `configuration_variant.lever_changes_json` is the structured diff from the base network, and
 * the field earns its place: it is what enables lever-level attribution of metric
 * improvements. The comparison view renders it as the annotation under each column -
 * which is what turns "variant 3 recovers two periods faster" into "+20% capacity at PLANT-1
 * recovers two periods faster". `POST /networks/{id}/clone` accepts it and nothing else in the UI
 * writes it, so without this field the annotation row is permanently empty.
 *
 * **It is a note, not a diff, and the dialog does not pretend otherwise.** The fork happens *before*
 * the edit - that is the whole point of the immutability rule - so at this moment the researcher
 * knows what they intend, not what they will do. The text is stored as `{ "note": "…" }`, which
 * `flattenLevers` renders as one annotation line. The structured vocabulary belongs to the Phase 2
 * configuration engine, which writes it properly when it persists an evaluated candidate; a shape
 * invented here would be a third opinion about a schema that already has an owner.
 *
 * ## The third choice (FR-20)
 *
 * Beside forking and cancelling, the prompt offers *discard this network's runs and edit in place*.
 * The two are the honest framing of one decision, and the dialog states it that way: **fork to keep
 * the result, discard to admit it was a test.** Iterative model building produces runs of the second
 * kind by the dozen - a run submitted only to see whether the model behaves at all - and before
 * FR-20 the only way to keep editing was to fork, which buried the researcher in variants nobody
 * wanted.
 *
 * It emits {@link discardRequested} rather than deleting anything. Deletion is irreversible and goes
 * through the typed confirmation of `shared/confirm-dialog` (FR-15's discipline, FR-20's phrase) -
 * this dialog does not have the run count or the restored-archive warning that confirmation must
 * carry, and inventing a second, weaker confirmation here would be exactly the kind of drift the
 * shared component exists to prevent.
 */
@Component({
  selector: 'app-fork-prompt',
  standalone: true,
  templateUrl: './fork-prompt.component.html',
  styleUrl: './fork-prompt.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ForkPromptComponent {
  readonly network = input.required<Network>();
  readonly busy = input(false);

  readonly forked = output<ForkRequest>();
  readonly cancelled = output<void>();
  /**
   * The third choice: discard this network's runs and edit in place (FR-20).
   *
   * Opens the typed confirmation; nothing is deleted from here. See the class note.
   */
  readonly discardRequested = output<void>();

  readonly name = signal('');
  /** What this variant is meant to change - recorded as the lever annotation. */
  readonly leverNote = signal('');

  /**
   * Resolves the two fields through `core/fork-request.forkRequestFrom` (FR-09, FR-26).
   *
   * The rule is shared with the dashboard's *Duplicate network* dialog rather than written here: a
   * blank name means "keep the same name and take the next version", a name equal to the base
   * network's means the same thing said explicitly, and a blank note records nothing at all - which
   * the comparison view renders differently from an empty annotation. This dialog offers the base
   * name as a **placeholder** where FR-26's prefills it as a value, and that difference is exactly
   * why the resolution has to be one function.
   */
  submit(): void {
    this.forked.emit(forkRequestFrom(this.name(), this.network().name, this.leverNote()));
  }

  onBackdropClick(event: MouseEvent): void {
    if (!this.busy() && event.target === event.currentTarget) {
      this.cancelled.emit();
    }
  }
}
