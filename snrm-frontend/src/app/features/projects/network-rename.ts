import { Network, NetworkRequest, ProblemCode } from '../../core/models';
import { describeNetwork } from './network-selection';

/**
 * Renaming a network from the project dashboard's row menu: a name is editable in the
 * table (FR-29).
 *
 * Pure - no Angular, no HTTP - for the reason `network-duplicate.ts`, `network-selection.ts` and
 * `core/run-discard.ts` are: the parts that have to be right are the shape of one request and the
 * wording of what it will do, and neither can be read off a component spec without rendering a
 * modal.
 *
 * ## The trap this module exists to hold in one place
 *
 * `PUT /networks/{id}` is a **full replacement of the client-supplied fields**, and there are two of
 * them: the name *and* the baseline flag. A rename that sent only the name would arrive with
 * `baseline` defaulting to `false` and would silently un-baseline the project - the comparison view
 * would lose the column it measures the others against, with nothing on screen having said so, and
 * the researcher's own act of tidying a filename would be what did it.
 *
 * That is the same failure the product catalogue already records for `PUT /products/{id}` ("editing
 * is name and unit value together": `unitValue` is a primitive, so an omitted one arrives as 0 and
 * zeroes every monetary metric weighted by it). That is the one trap that belongs on the
 * surface rather than in the API, and {@link renameRequest} is that
 * surface: **the only place in this feature that builds the body**, and it sources the flag from the
 * row's own current state rather than from a form control, because the dialog does not offer to
 * change it and must not be able to.
 *
 * ## A frozen network renames, and nothing here reads `editable`
 *
 * The freeze covers everything a result was computed *from* - nodes, links,
 * per-product rows, the time base - and covers neither the name nor the baseline flag.
 * Neither is an input to any metric, appears in any snapshot or is read by any calculator, so
 * freezing them protects nothing the rule exists to protect. The backend's guard came off
 * `NetworkService.update` and off nothing else. So there is no gate, no fork prompt and no typed
 * confirmation here, and {@link renameConfirm} says why on a frozen row rather than staying silent -
 * a reader who has met FR-15's missing Delete has already learned that the *Frozen* badge takes
 * controls away.
 *
 * The wording never calls a rename an edit, in either branch. It is not one: the freeze is a
 * rule about *structure*, and a label is not structure.
 *
 * ## Nothing checks the name against the project
 *
 * A duplicate name is not blocked here, deliberately - the same rule `data-import/file-names.ts`
 * states for an imported network's name, and for its reason: a client-side check would be a second
 * implementation of a server-side uniqueness rule, free to disagree with it. What the server
 * actually enforces is `uq_network (project_id, name, version)`, and **a rename does not renumber**
 * (`NetworkService.update` writes the name and the flag and touches nothing else, unlike `create`
 * and `clone`, which both take `findMaxVersion(name) + 1`). So renaming `Baseline_v3_FINAL v1` to
 * `Baseline` succeeds while no `Baseline v1` exists and is refused with 409 `CONSTRAINT_VIOLATION`
 * when one does - an outcome the client reports rather than predicts, through
 * {@link renameRefusalNote}, because the refusal's own sentence is deliberately vague about which
 * constraint objected.
 */

/** The server's own limit - `@Size(max = 160)` on `NetworkRequest.name`. */
export const NETWORK_NAME_MAX = 160;

/** Everything `shared/confirm-dialog` needs for one rename, assembled in one place. */
export interface RenameConfirm {
  readonly title: string;
  readonly message: string;
  /** What the rename touches, and - mostly - what it does not. */
  readonly details: readonly string[];
  readonly nameLabel: string;
  readonly nameHint: string;
  readonly confirmLabel: string;
}

/** Why the rename cannot be sent yet, and whether that is the user's text being wrong. */
export interface RenameBlocker {
  readonly reason: string;
  /**
   * True when the typed name would be refused by the server's own validation, false when it is
   * merely the name the network already has.
   *
   * The two are shown differently - an error against the field, versus a muted note under it - but
   * both disable the action, and the reason is on screen in either case rather than left to a
   * disabled button to imply.
   */
  readonly invalid: boolean;
}

/** What one rename did: the row as it was, and the row the server answered with. */
export interface RenameResult {
  readonly before: Network;
  readonly after: Network;
}

/**
 * The body of `PUT /networks/{id}` for a rename - **name and baseline flag, always both**
 * (FR-29).
 *
 * ## Read this before changing it
 *
 * The endpoint replaces both fields. `NetworkRequest.baseline` is a primitive `boolean` on the
 * backend, so omitting it is not "leave it alone" - it arrives as `false`, and the network stops
 * being the project's baseline. A rename is the most innocuous thing a researcher does to a network
 * and un-baselining a project is one of the more consequential, so the two must never be able to
 * meet: this function is the only place in the feature that builds this body, and it takes the flag
 * from `network.baseline` - the row's current state, straight from the last server response - rather
 * than from any control, because the dialog deliberately does not offer to change it.
 *
 * `NetworksStore.rename` calls this and nothing else assembles a `NetworkRequest` for a `PUT`. The
 * product catalogue's `PUT /products/{id}` states the identical rule for `unitValue` for the
 * identical reason - editing is name and unit value together; this is that rule at its
 * second occurrence, which is exactly when it stops being an anecdote.
 *
 * One property worth knowing, because it is what makes sending the flag *safe* rather than merely
 * correct: the backend only runs `assertNoOtherBaseline` when the request asks for a flag the
 * network does not already hold (`request.baseline() && !network.isBaseline()`). Sending the row's
 * own value can never satisfy that, so a rename cannot be refused with `BASELINE_ALREADY_SET` - it
 * carries the flag across without ever re-asserting it.
 *
 * The name is **trimmed on the way out**, so the string this client validated is the string the
 * server validates: `@Size(max = 160)` is checked before `NetworkService.update` trims, and a name
 * of 160 characters followed by a space would otherwise pass here and be refused there.
 * `products.store.normalise` trims for the same reason.
 */
export function renameRequest(network: Network, typed: string): NetworkRequest {
  return {
    name: typed.trim(),
    // Never omitted, never hard-coded, never taken from a form control. See the note above.
    baseline: network.baseline,
  };
}

/**
 * Why the rename cannot be sent, or null when it can (FR-29).
 *
 * The two invalid cases are the server's own validation restated, against the trimmed string this
 * client will actually send: `@NotBlank` and `@Size(max = 160)` on `NetworkRequest.name`. They are
 * restated rather than left to the 400 because a length limit is a fact about the field the user is
 * typing into, and finding it out from a red banner after pressing a button is the worst moment.
 *
 * **A duplicate name is not among them.** See the module note: uniqueness is `uq_network`'s to
 * enforce, and a client-side check over the loaded list would be a second implementation of it that
 * could disagree - it would also have to model the version number, which a rename does not move.
 */
export function renameBlocker(network: Network, typed: string): RenameBlocker | null {
  const name = typed.trim();

  if (name.length === 0) {
    return {
      reason: 'A network needs a name. Type one, or cancel to leave it as it is.',
      invalid: true,
    };
  }
  if (name.length > NETWORK_NAME_MAX) {
    return {
      reason:
        `That is ${name.length} characters. A network name is at most ${NETWORK_NAME_MAX}, which ` +
        'is the limit the server enforces - shorten it by ' +
        `${name.length - NETWORK_NAME_MAX}.`,
      invalid: true,
    };
  }
  if (name === network.name) {
    return {
      reason:
        'This is the name it already has. Type a different one to rename it, or cancel - nothing ' +
        'is sent either way.',
      invalid: false,
    };
  }
  return null;
}

/**
 * The confirmation for renaming one network (FR-29).
 *
 * ## Why a dialog rather than an editable cell
 *
 * The row menu is where FR-26 put the row's secondary actions, and a rename is one; but the deciding
 * argument is that two of the four things below cannot be said in a table cell. The reader has to be
 * told that the baseline flag is travelling with the name - it is being *sent*, which is the trap
 * of this feature - and, on a frozen row, that the freeze does not reach a name. An
 * inline field says neither, and a table where one cell edits in place and every other secondary
 * action is behind a menu is two idioms for one column.
 *
 * ## No typed phrase, and never one
 *
 * Nothing is destroyed and nothing structural changes, so FR-15's typed discipline would be friction
 * spent teaching the user to type through it - the judgement `duplicateConfirm` and
 * `selectionExportConfirm` already make, and the reason `shared/confirm-dialog` treats a null
 * `requiredPhrase` as "no check". A rename is undone by renaming it back.
 *
 * @param network the row being renamed - read for its name, its version, its baseline flag and,
 *   only to add the last detail line, whether a run has frozen it
 */
export function renameConfirm(network: Network): RenameConfirm {
  const details: string[] = [
    // The trap, said to the reader in the reader's terms. The code that makes it true is
    // `renameRequest`; this is the same fact stated where somebody can notice it is wrong.
    network.baseline
      ? 'This network is the project’s baseline, and it stays the baseline. The name and the ' +
        'baseline flag are replaced together by one request, so the flag is sent exactly as it ' +
        'stands here - renaming cannot move it, and the comparison view keeps the column it ' +
        'measures the others against.'
      : 'The baseline flag is sent unchanged with the name - the two are replaced together by one ' +
        'request. This network is not the project’s baseline, and renaming it will not make it ' +
        'one, or disturb whichever network is.',
    'Nothing else moves: the same nodes, links and per-product rows, the same clock, the same ' +
      'canvas layout, and the same simulation runs and results. A name is a label on a ' +
      'configuration, not part of it.',
    `The version number stays v${network.version}. A rename replaces the name alone - creating a ` +
      'network and duplicating one are what take the next number under a name; this ' +
      'does not.',
    'The name does not have to be unique here, and nothing checks it against the project before ' +
      'sending: a name plus a version number is what has to be unique, and the server is the one ' +
      'that decides. If that pair is already taken it refuses the rename and nothing changes.',
  ];

  if (!network.editable) {
    // The row is badged *Frozen* and offers no Delete (FR-15), so the reader has already learned
    // that the badge takes controls away. Say why this one is not among them - and say it without
    // ever calling a rename an edit, because it is not one.
    details.push(
      'A simulation run has frozen this network, and it renames like any other. The freeze ' +
        'covers what a result was computed *from* - nodes, links, per-product rows, the ' +
        'time base - and a name is none of those: it is not an input to any metric and appears in ' +
        'no result. There is nothing to fork and nothing to discard first, and the run against ' +
        'this network is untouched.',
    );
  }

  return {
    title: 'Rename this network?',
    message:
      `Give ${describeNetwork(network)} a different name. A name assigned at creation or taken ` +
      'from a file name at import is a label rather than a decision (FR-29), so it is changed here ' +
      'in the table, where you can see what the network holds. You stay on this page; the row ' +
      'updates in place.',
    details,
    nameLabel: 'New name',
    nameHint:
      `Currently “${network.name}”. Up to ${NETWORK_NAME_MAX} characters, and it is trimmed before ` +
      'it is sent. Variants of one name sort together in version order, so naming this one after ' +
      'an existing network groups it with that network’s rows rather than starting a group of its ' +
      'own.',
    confirmLabel: 'Rename network',
  };
}

/**
 * What to say once the row has come back renamed (FR-29).
 *
 * A sentence above the table rather than a navigation, exactly as `duplicateOutcome` is, and for a
 * reason of its own: the table sorts by name, so a renamed row **moves**, and a reader who pressed a
 * button on the fourth row and found nothing changed there is looking at a bug until told
 * otherwise. Both labels go through `describeNetwork`, the same one every dialog and report on this
 * page uses.
 *
 * It reads the server's answer rather than the request, which is what makes the last two clauses
 * honest: if the version or the flag came back different from what was sent, this says so instead
 * of describing what the client asked for.
 */
export function renameOutcome(before: Network, after: Network): string {
  const parts = [`${describeNetwork(before)} is now ${describeNetwork(after)}.`];

  if (before.name !== after.name) {
    parts.push(
      'The table sorts by name, so its row has moved to where the new name falls - variants of one ' +
        'name stay together, in version order.',
    );
  }
  if (before.version !== after.version) {
    // Not what `PUT /networks/{id}` does today; said from the response rather than assumed, so a
    // server that ever does renumber is reported rather than contradicted.
    parts.push(`Its version number moved from v${before.version} to v${after.version}.`);
  }
  parts.push(
    after.baseline
      ? 'It is still the project’s baseline - the flag travelled with the name, which is the whole ' +
        'reason the two are sent together.'
      : 'Its baseline flag is unchanged, and so is the project’s baseline.',
  );
  if (!after.editable) {
    parts.push('It is still frozen by its runs, and they are untouched: a name is not structure.');
  }

  return parts.join(' ');
}

/**
 * What to add beneath a refused rename's own sentence, or null.
 *
 * Keyed off the RFC 7807 `code` and never off the sentence, the rule `network-selection.outcomeNote`
 * already follows. Two of the three are worth printing because the server's own wording cannot be
 * more specific than it is:
 *
 * `CONSTRAINT_VIOLATION` is the interesting one. The backend answers a schema-level conflict with a
 * deliberately vague sentence - "the request conflicts with data that already exists" - because a
 * constraint name is an internal detail and the SQL text can carry the row's values. Here there is
 * only one constraint the request can have hit, `uq_network (project_id, name, version)`, so the
 * remedy is nameable even though the refusal is not.
 *
 * `NETWORK_IMMUTABLE` should be unreachable: the guard came off `NetworkService.update`
 * (FR-29). Seeing it means this frontend is talking to a backend from before that, which is a
 * sentence worth printing once rather than a mystery worth debugging twice.
 */
export function renameRefusalNote(code: string | null): string | null {
  switch (code) {
    case ProblemCode.CONSTRAINT_VIOLATION:
    case ProblemCode.DUPLICATE_NAME:
      return (
        'A network in this project already has that name at this version number, and the pair has ' +
        'to be unique. A rename does not take the next version number - creating and duplicating ' +
        'do - so either choose a name that is free at this version, or use Duplicate ' +
        'network to make a copy under the existing name, which does renumber. Nothing was changed.'
      );
    case ProblemCode.NETWORK_IMMUTABLE:
      return (
        'The server refused this as an edit to a frozen network. A rename is not a structural edit ' +
        'and is not supposed to be refused (FR-29), so this backend ' +
        'predates that change - the frontend is doing the right thing and the API has not caught ' +
        'up. Nothing was changed.'
      );
    case ProblemCode.NOT_FOUND:
      return 'It was already gone - something else deleted it first. Refresh the list.';
    default:
      return null;
  }
}
