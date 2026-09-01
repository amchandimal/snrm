import { Id, Network, ProblemCode } from '../../core/models';
import { ARCHIVE_IS_A_COPY, RESTORE_CREATES_A_NEW_PROJECT, ruleSentence } from './archive-rules';

/**
 * Selecting several networks on the project dashboard, and what a set delete may do
 * (FR-23).
 *
 * Pure - no Angular, no HTTP - for the reason `core/run-discard.ts` and `provenance-tree.ts` are:
 * the parts that have to be right are a rule about which rows still exist and the wording of an
 * irreversible question, and neither can be read off a component spec without rendering a table and
 * a modal.
 *
 * ## The three rules this module holds
 *
 * **A selection is reconciled against the list, never trusted across it.** A network that is no
 * longer there leaves the selection with it, so an action can never be aimed at a row that has
 * gone. {@link reconcileSelection} is that rule: every time the list changes -
 * a reload, a create, a delete - the selection is intersected with what is actually there. The ids
 * are all the store keeps, so a selected network that another browser tab deleted stops being
 * selected the moment this tab re-reads the list, rather than becoming a `DELETE` aimed at a 404.
 *
 * **FR-15 is not relaxed by there being several.** A network frozen by a simulation run is not
 * deletable - the same rule the per-row Delete obeys by not offering the button at all.
 * A set may hold both kinds, so {@link splitSelection} splits it and
 * {@link selectionDeleteConfirm} names both halves *above* the typed phrase. The user confirms a
 * split they have seen; a dialog that took six and quietly deleted four would be reporting the
 * shortfall after the irreversible half.
 *
 * **Each network is refused or accepted on its own terms**, so the outcome is per network
 * ({@link DeletionOutcome}, {@link deletionHeadline}). The deletes are issued one at a time for
 * that reason and not for a technical one; the alternative is a bulk endpoint, and that is
 * the answer if a project ever holds enough networks for the request count to matter, and nothing
 * about the surface would change. See `NetworksStore.deleteMany`, where the requests are made.
 *
 * ## The set has a second action, and it is not destructive
 *
 * {@link selectionExportConfirm} is FR-24's "Export as a project": the same selection, written out
 * as a standalone project archive. It shares this module because it asks a question *about a
 * selection* and the wording is the part that has to be right - but it deliberately shares nothing
 * else with the delete. No typed phrase, no split on `editable`, no per-network outcome: a copy is
 * not an irreversible act, a frozen network exports exactly like an editable one, and the whole
 * selection travels in one request or none of it does.
 *
 * ## Why the typed phrase is the project's name, unchanged
 *
 * FR-15 asks for the owning **project's** name and this dialog asks for exactly that string - not a
 * second phrase rule invented for the plural case. A network shares its name with every variant of
 * it, so no network's own name identifies which ones are going, and a phrase derived from
 * the selection would change as the selection did: the user would be typing a different string for
 * every set, which is a check nobody reads. The project name is on screen above the table, it is
 * the same string the single-network dialog asks for, and the *names of what is going* are in the
 * two lists directly above the field.
 */

/** A selection sorted into what FR-15 permits and what it refuses. */
export interface SelectionSplit {
  /** Editable networks - these are what a delete will actually be issued for. */
  readonly deletable: readonly Network[];
  /** Frozen by a run, so not deletable at all. Named, never silently dropped. */
  readonly blocked: readonly Network[];
}

/** Everything `shared/confirm-dialog` needs for one set delete, assembled in one place. */
export interface SelectionDeleteConfirm {
  readonly title: string;
  readonly message: string;
  readonly details: readonly string[];
  /** Heading over the "these will be deleted" list. */
  readonly deletableHeading: string;
  /** Heading over the "these are frozen and will not" list, or null when nothing is frozen. */
  readonly blockedHeading: string | null;
  /** FR-20's discard, named as the way to move a network from the second list to the first. */
  readonly blockedRemedy: string | null;
  /** Typed before the action is enabled: the owning project's name (FR-15) - see the module note. */
  readonly requiredPhrase: string;
  readonly phraseLabel: string;
  readonly confirmLabel: string;
}

/** What happened to one network of a set delete - accepted or refused, on its own terms. */
export interface DeletionOutcome {
  readonly network: Network;
  readonly status: 'deleted' | 'refused';
  /** The server's own sentence for a refusal; null for a success. Never parsed, only shown. */
  readonly message: string | null;
  /** RFC 7807 `code` of a refusal; null otherwise. Branch on this, never on the sentence. */
  readonly code: string | null;
}

/** How many networks are named before the rest are summarised, matching `run-discard.ts`. */
const LISTED_NETWORKS = 12;

/**
 * The selection with everything that is no longer in the list removed (FR-23).
 *
 * Returns the **same set instance** when nothing was dropped, so assigning it back to a signal on
 * every list read is not a change and wakes nothing up. The size comparison is sufficient for that:
 * the result is built only from members of `selected`, so it is always a subset, and a subset of
 * equal size is the same set.
 *
 * Call this wherever the list is set - not from a component effect watching it. The list and the
 * selection reconciled against it must never be two separate updates, or there is a moment in
 * between where a menu is enabled for a row that has gone.
 */
export function reconcileSelection(
  selected: ReadonlySet<Id>,
  networks: readonly Network[],
): ReadonlySet<Id> {
  if (selected.size === 0) {
    return selected;
  }
  const present = new Set<Id>();
  for (const network of networks) {
    if (selected.has(network.id)) {
      present.add(network.id);
    }
  }
  return present.size === selected.size ? selected : present;
}

/**
 * The selected networks, in the order the table draws them.
 *
 * List order rather than selection order, so the dialog's two lists read down the screen the way
 * the rows the user ticked do. It is also the intersection, which makes it the only thing any
 * action is ever aimed at: a stale id in the set cannot reach a request through here.
 */
export function selectionOf(
  networks: readonly Network[],
  selected: ReadonlySet<Id>,
): readonly Network[] {
  return networks.filter((network) => selected.has(network.id));
}

/** The selection with one id added or removed; the same instance when it was already that way. */
export function toggled(selected: ReadonlySet<Id>, id: Id, on: boolean): ReadonlySet<Id> {
  if (selected.has(id) === on) {
    return selected;
  }
  const next = new Set(selected);
  if (on) {
    next.add(id);
  } else {
    next.delete(id);
  }
  return next;
}

/**
 * Sorts a selection into what FR-15 permits and what it refuses.
 *
 * `editable` is the server's word for it and the only signal read - the same field the per-row
 * Delete button is gated on, so a row that offers no Delete lands in `blocked` here by
 * construction rather than by a second rule that could drift from it.
 */
export function splitSelection(selected: readonly Network[]): SelectionSplit {
  return {
    deletable: selected.filter((network) => network.editable),
    blocked: selected.filter((network) => !network.editable),
  };
}

/** `Baseline v2 (#7)` - name, version and id, because a name alone names every variant. */
export function describeNetwork(network: Network): string {
  return `${network.name} v${network.version} (#${network.id})`;
}

/**
 * What deleting a network takes with it - said once, for one row and for a set (FR-15, FR-23).
 *
 * The per-row Delete of FR-15 and the set Delete of FR-23 are the same irreversible act at two
 * counts, so they list the same consequences. They were two near-copies until FR-26 moved the
 * per-row control into the row menu - the moment a control moves is the moment its wording is most
 * likely to drift, and the rule that governs `core/run-discard.ts` applies here for the same reason:
 * **one irreversible act must not be described two ways.**
 *
 * The variant clause is the one worth reading twice, and it applies at both counts: deleting
 * `Baseline v1` drops the fork note recorded against `Baseline v2`, which is *staying*. The lineage
 * beneath the table loses an edge that nothing on the confirmation would otherwise have mentioned.
 *
 * @param count how many networks are going - the sentences agree with it rather than hedging
 */
export function deletionDetails(count: number): readonly string[] {
  const one = count === 1;
  return [
    one
      ? 'Every node and link in it, with their per-product demand, inventory and cost rows.'
      : 'Every node and link in each network, with their per-product demand, inventory and cost ' +
        'rows.',
    `Any configuration-variant record pointing at ${one ? 'it' : 'one of them'} - including a fork ` +
      'note recorded against a network that is staying, if the network it was forked from is going.',
    'Products are project-scoped and are not deleted.',
  ];
}

/**
 * Why a set delete cannot be offered at all, or null (FR-15).
 *
 * Every selected network is frozen, so there is nothing to confirm: no request would be issued and
 * asking someone to type a project name for a no-op is friction that buys nothing. Shown as a
 * banner instead, naming the networks and the remedy - the same judgement
 * `core/run-discard.activeRunsBlocker` makes for a discard the server would refuse whole.
 */
export function frozenSelectionBlocker(split: SelectionSplit): string | null {
  if (split.deletable.length > 0 || split.blocked.length === 0) {
    return null;
  }
  const { blocked } = split;
  return (
    `${blocked.length === 1 ? 'The selected network is' : `All ${blocked.length} selected networks are`} ` +
    `frozen by a simulation run, so nothing would be deleted (${listNetworks(blocked)}). ` +
    'A run’s results are only meaningful beside the exact structure that produced them, ' +
    'and selecting several does not relax that. Discard a network’s runs from its ' +
    'editor - the frozen banner’s Unlock… (FR-20) - and it becomes deletable again.'
  );
}

/**
 * The confirmation for deleting a selection (FR-23).
 *
 * Call only when {@link frozenSelectionBlocker} is null - there is at least one deletable network.
 *
 * @param projectName the owning project's name, which is the typed phrase (FR-15). Empty while the
 *   project is still loading, which `shared/confirm-dialog` treats as unsatisfiable rather than as
 *   satisfied, so the action cannot be enabled before the dialog knows what it is asking for.
 */
export function selectionDeleteConfirm(
  projectName: string,
  split: SelectionSplit,
): SelectionDeleteConfirm {
  const going = split.deletable.length;
  const frozen = split.blocked.length;
  const selected = going + frozen;

  const details: string[] = [
    // Shared with the per-row Delete of FR-15, which lists exactly these (FR-26).
    ...deletionDetails(going),
    going === 1
      ? 'The delete is one request and its outcome is reported on its own terms.'
      : `The ${going} deletes are issued one network at a time and each is reported on its own ` +
        'terms: one being refused does not stop the rest, and the list above the table will say ' +
        'exactly which went and which did not.',
  ];

  return {
    title:
      frozen > 0
        ? `Delete ${going} of the ${selected} selected networks?`
        : going === 1
          ? 'Delete this network?'
          : `Delete these ${going} networks?`,
    message:
      `Delete ${going === 1 ? 'this network' : `these ${going} networks`} and everything in ` +
      `${going === 1 ? 'it' : 'them'}. This cannot be undone.` +
      (frozen === 0
        ? ''
        : frozen === 1
          ? ' One more selected network is frozen and will be left exactly as it is.'
          : ` ${frozen} more selected networks are frozen and will be left exactly as they are.`),
    details,
    deletableHeading:
      going === 1 ? 'This will be deleted' : `These ${going} will be deleted`,
    blockedHeading:
      frozen === 0
        ? null
        : frozen === 1
          ? 'This one is frozen and will not be deleted'
          : `These ${frozen} are frozen and will not be deleted`,
    blockedRemedy:
      frozen === 0
        ? null
        : `A completed simulation run holds ${frozen === 1 ? 'it' : 'them'}, and ` +
          'selecting several does not relax that rule. To delete one anyway, open it in the ' +
          'editor and discard its runs from the frozen banner’s Unlock… (FR-20); it ' +
          'becomes editable, and then deletable, as soon as the last run holding it is gone.',
    requiredPhrase: projectName,
    phraseLabel: 'project name',
    confirmLabel: going === 1 ? 'Delete 1 network' : `Delete ${going} networks`,
  };
}

/**
 * Everything the FR-24 export confirmation needs, assembled in one place.
 *
 * Shaped like {@link SelectionDeleteConfirm} but deliberately **not** typed: it has no
 * `requiredPhrase` and never will. `shared/confirm-dialog` treats a null phrase as "no check", which
 * is the right answer for an action that copies - the typed discipline of FR-15 is sized to *this
 * cannot be undone*, and spending it on a download would teach the user to type through it.
 */
export interface SelectionExportConfirm {
  readonly title: string;
  readonly message: string;
  /** The two rules of `archive-rules.ts`, as sentences. */
  readonly details: readonly string[];
  /** Heading over the list of networks that will travel. */
  readonly listHeading: string;
  /** What the archive carries with them, beyond the networks themselves. */
  readonly carries: readonly string[];
  readonly confirmLabel: string;
}

/**
 * The confirmation for exporting a selection as a standalone project (FR-24).
 *
 * ## Why a dialog at all, when nothing is destroyed
 *
 * Because two of the three things a reader needs are not guessable from a menu entry reading
 * "Export as a project…", and the third is a list they have to check. **It copies** - a researcher
 * is entitled to assume the worst of a control that does not say, and the worst here is that the
 * runs left with the file. **A restore creates a new project** - "export as a project" is one word
 * away from meaning "move these into a project". And *which* networks: the selection is a set of
 * ticked boxes several rows apart, so naming them is the last chance to notice a mis-tick before a
 * file exists that is wrong in a way nothing about it will announce.
 *
 * The two rules come from `archive-rules.ts` verbatim, which is the point of that module: the
 * restore card on the project list makes the same two claims, and a version of them that drifted
 * would be read as two different rules rather than one.
 *
 * ## What it says the archive carries
 *
 * The three carry rules, in the reader's terms rather than the manifest's. Two of them are
 * *surprises in the generous direction* - a catalogue and a scenario set larger than the selection
 * strictly needs - and one is a genuine loss, so all three are stated: a restored project holding a
 * product nothing uses reads as a defect unless it was announced, and a scenario that refuses to
 * run reads as a defect *especially* if it was not.
 */
export function selectionExportConfirm(selected: readonly Network[]): SelectionExportConfirm {
  const count = selected.length;
  const frozen = selected.filter((network) => !network.editable).length;

  return {
    title:
      count === 1
        ? 'Export this network as a project?'
        : `Export these ${count} networks as a project?`,
    message:
      `Downloads ${count === 1 ? 'this network' : `these ${count} networks`} as one project ` +
      'archive - the ordinary archive of this project, narrowed to what you ticked. Restoring it ' +
      `gives you a separate project holding exactly ${count === 1 ? 'it' : 'them'}.`,
    details: [ruleSentence(ARCHIVE_IS_A_COPY), ruleSentence(RESTORE_CREATES_A_NEW_PROJECT)],
    listHeading:
      count === 1 ? 'This network will travel' : `These ${count} networks will travel`,
    carries: [
      frozen === 0
        ? 'Each network in full: its nodes and links with their units, its per-product rows and ' +
          'its canvas layout - the same XML document its own Export XML writes.'
        : 'Each network in full - nodes, links, per-product rows, canvas layout - and the ' +
          'completed simulation runs of ' +
          (frozen === 1 ? 'the frozen one' : `the ${frozen} frozen ones`) +
          ', with their parameters, seeds, metric results and time series. Runs that failed, were ' +
          'cancelled or are still queued carry no results and are left out.',
      'The whole product catalogue, including any product none of these networks uses. A product ' +
        'is named by name on every demand and inventory row, so carrying only the ones in use ' +
        'would risk writing a reference the restore cannot resolve - an unused entry is harmless, ' +
        'a dangling one is not.',
      'Every disruption scenario in this project, because a scenario is project-scoped and is ' +
        'replayed against variants. An event aimed at a network you did not tick is restored ' +
        'unresolved, so that scenario visibly refuses to run rather than running with a ' +
        'disruption silently missing - repoint it in the scenario builder and it runs.',
      'Fork notes whose parent network you did not tick are dropped - the lineage has nothing to ' +
        'hang from - and the restore report counts them rather than passing over them.',
    ],
    confirmLabel: count === 1 ? 'Export 1 network' : `Export ${count} networks`,
  };
}

/**
 * The one-line verdict over a finished (or abandoned) set delete.
 *
 * States what went **and** what did not in the same sentence, in that order, because a run that
 * stopped part-way is exactly when a headline reading only "3 deleted" would be read as the whole
 * answer.
 */
export function deletionHeadline(outcomes: readonly DeletionOutcome[]): string {
  const deleted = outcomes.filter((outcome) => outcome.status === 'deleted').length;
  const refused = outcomes.length - deleted;

  if (outcomes.length === 0) {
    return 'Nothing was deleted.';
  }
  if (refused === 0) {
    return deleted === 1 ? '1 network deleted.' : `All ${deleted} networks deleted.`;
  }
  if (deleted === 0) {
    return refused === 1
      ? 'The network was not deleted - the server refused it.'
      : `None of the ${refused} networks was deleted - the server refused every one.`;
  }
  return (
    `${deleted} of ${outcomes.length} networks deleted; ${refused} ` +
    `${refused === 1 ? 'was refused and is still here' : 'were refused and are still here'}.`
  );
}

/**
 * What to add beneath a refusal's own sentence, or null.
 *
 * Keyed off the RFC 7807 `code` and never off the sentence. Only two codes have a remedy
 * worth printing, and `NETWORK_IMMUTABLE` is the interesting one: the dashboard filters frozen
 * networks out of the delete before issuing anything, so reaching it here means the freeze landed
 * between the list being read and the request being sent - which is precisely the case a per-network
 * outcome exists to report, and precisely what a single "the delete failed" toast would hide.
 */
export function outcomeNote(outcome: DeletionOutcome): string | null {
  switch (outcome.code) {
    case ProblemCode.NETWORK_IMMUTABLE:
      return (
        'A simulation run froze this network between the list being read and the request being ' +
        'sent. Refresh to see its state, and discard its runs from the editor (FR-20) ' +
        'if you still want it gone.'
      );
    case ProblemCode.NOT_FOUND:
      return 'It was already gone - something else deleted it first. Refresh the list.';
    default:
      return null;
  }
}

/** Network labels for a sentence, capped so a fifty-row selection does not fill the screen. */
function listNetworks(networks: readonly Network[]): string {
  const shown = networks.slice(0, LISTED_NETWORKS).map(describeNetwork);
  const hidden = networks.length - shown.length;
  return hidden > 0 ? `${shown.join('; ')}, and ${hidden} more` : shown.join('; ');
}
