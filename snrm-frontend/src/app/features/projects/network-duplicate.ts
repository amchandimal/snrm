import { Network } from '../../core/models';
import { describeNetwork } from './network-selection';

/**
 * Duplicating a network from the project dashboard's row menu: duplicating is forking, and
 * the tree is why (FR-26).
 *
 * Pure - no Angular, no HTTP - for the reason `network-selection.ts` and `core/run-discard.ts` are:
 * the part that has to be right is how the question is put, and none of it can be read off a
 * component spec without rendering a modal.
 *
 * ## What the dialog has to establish, and why each sentence is here
 *
 * **It is a fork, not a copy.** The action calls the clone endpoint, so the copy is a
 * `CONFIGURATION_VARIANT` recorded against its base, and it appears in the
 * provenance tree under the network it came from. That is the whole reason a duplicate is offered
 * here rather than a bare copy being made - an untracked copy would be the one network in the tree
 * whose parent nobody could name. A reader pressing *Duplicate network* has every reason to expect
 * an unattached copy, so the dialog says otherwise before it happens rather than leaving the
 * lineage panel to be a surprise.
 *
 * **The name is a default, not a placeholder.** It arrives prefilled with the base network's name,
 * which is "the documented way to take the next version number and sort beside it" - the server
 * takes `findMaxVersion(name) + 1`, and `sortNetworks` puts variants of one name together in
 * version order. Left alone it produces `Baseline v4` under `Baseline v1`; changed, it starts a
 * name of its own at v1 and sorts elsewhere, which the hint says in as many words because it is the
 * one thing about this field a reader can get wrong without noticing.
 *
 * **The lever note is the same note the fork prompt collects.** A fork whose reason is
 * recorded is what turns the comparison matrix from a scoreboard into a finding. Optional, phrased
 * as intent - the copy is made before it is edited, so at this moment the researcher knows what
 * they mean to change, not what they will change.
 *
 * **A frozen network duplicates like any other.** Ungated, exactly as its exports are: the freeze
 * covers *edits*, and a clone reads its source. The dialog says so on a frozen row rather than
 * staying silent, because the row it was opened from is badged *Frozen* and a reader who has met
 * FR-15's missing Delete has already learned that the badge takes things away.
 *
 * ## No typed phrase, and never one
 *
 * Nothing is destroyed and nothing moves, so FR-15's typed discipline would be friction spent
 * teaching the user to type through it - the judgement `selectionExportConfirm` already makes for
 * FR-24's export, and the reason `shared/confirm-dialog` treats a null `requiredPhrase` as "no
 * check". What the dialog is for is the two fields and the three claims above.
 */

/** Everything the duplicate dialog renders, assembled in one place. */
export interface DuplicateConfirm {
  readonly title: string;
  readonly message: string;
  /** What the copy carries, and what it does not. */
  readonly details: readonly string[];
  readonly nameLabel: string;
  /** Why the prefilled name is the version-number rule rather than a suggestion. */
  readonly nameHint: string;
  readonly leverLabel: string;
  readonly leverHint: string;
  readonly leverPlaceholder: string;
  readonly confirmLabel: string;
}

/** The placeholder in the lever field - the same examples the editor's fork prompt offers. */
const LEVER_PLACEHOLDER = 'e.g. +20% capacity at PLANT-1, backup supplier for DC-2';

/**
 * The confirmation for duplicating one network (FR-26).
 *
 * @param network the network being duplicated - the base of the variant this will record
 */
export function duplicateConfirm(network: Network): DuplicateConfirm {
  const details: string[] = [
    'Every node and link, with their per-product demand, inventory and cost rows, and the canvas ' +
      'layout - the same copy the editor’s fork prompt makes.',
    'The base network’s clock: its period length, horizon and rounding policy. The time base is ' +
      'not a lever - two configurations compared side by side have to step on the same grid, or ' +
      'the comparison measures the time model rather than the configuration.',
    'A configuration-variant record pointing back at ' +
      `${describeNetwork(network)}, which is what puts the copy under it in the lineage below the ` +
      'table and annotates its column in the comparison view.',
    'No simulation runs and no results. The copy starts editable and unevaluated; nothing about ' +
      `${describeNetwork(network)} is changed, moved or removed.`,
  ];

  if (!network.editable) {
    // The row is badged *Frozen* and offers no Delete (FR-15), so a reader has already learned that
    // the badge takes controls away. Say why this one is not among them.
    details.push(
      'This network is frozen by a simulation run, and it duplicates like any other. The freeze ' +
        'is about *edits*, and reading a configuration to copy it is not one - forking ' +
        'a frozen network is the remedy the freeze itself advertises, and its results are usually ' +
        'why it is worth building on.',
    );
  }

  return {
    title: 'Duplicate this network?',
    message:
      `Fork ${describeNetwork(network)} into a new configuration variant. It is recorded against ` +
      'this network, so it appears in the lineage beneath the table under the one it came from - ' +
      'a duplicate is a variant, not an untracked copy. You stay on this page; the copy ' +
      'joins the table.',
    details,
    nameLabel: 'Name for the copy',
    nameHint:
      `Prefilled with this network’s name, which is not a suggestion: keeping it takes the next ` +
      `version number, so the copy is ${network.name} v${network.version + 1} or later and sorts ` +
      'directly beside its base. Type a different name and the copy starts that name at v1 and ' +
      'sorts elsewhere.',
    leverLabel: 'What is this variant meant to change?',
    leverHint:
      'Optional, and recorded on the variant. The comparison view shows it under this ' +
      'configuration’s column and the lineage shows it beside the row, which is what turns “this ' +
      'one recovers faster” into a statement about a lever.',
    leverPlaceholder: LEVER_PLACEHOLDER,
    confirmLabel: 'Duplicate network',
  };
}

/**
 * What to say once the copy exists (FR-26).
 *
 * FR-26 asks that the new network be *identifiable* - it shares its name with its base and differs
 * by a version number, which is exactly the pair `describeNetwork` exists to disambiguate - and
 * that the researcher not be navigated away from the table they are working in. So the outcome is a
 * sentence above the table naming both networks and pointing at the two places the copy has
 * appeared, rather than a redirect into an editor nobody asked for.
 *
 * Both names go through `describeNetwork`, the same label the delete dialogs and the deletion report
 * use: one network must not be named two ways on one screen.
 */
export function duplicateOutcome(base: Network, copy: Network): string {
  const renamed = copy.name !== base.name;
  return (
    `${describeNetwork(copy)} was forked from ${describeNetwork(base)}. ` +
    (renamed
      ? 'You named it differently, so it starts its own version series and sorts under its own ' +
        'name in the table - the lineage below still shows it under the network it came from.'
      : 'It shares its base network’s name and took the next version number, so it sits directly ' +
        'beneath it in the table.') +
    ' It is in the lineage below as a fork of ' +
    `${base.name} v${base.version}.`
  );
}
