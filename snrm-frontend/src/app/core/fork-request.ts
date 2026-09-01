import { CloneNetworkRequest, LeverChanges } from './models';

/**
 * What a fork asks for, and the two rules that turn it into the body of
 * `POST /networks/{id}/clone` (FR-09, FR-26).
 *
 * Pure - no Angular, no HTTP - for the reason `core/run-discard.ts` and `core/lever-changes.ts`
 * are: **two features now build one request.** The editor's fork prompt has always collected a name
 * and a lever note, and FR-26 adds a second collector on the project dashboard -
 * *Duplicate network* on a row, which is the same fork made without opening the network first. The
 * two dialogs are worded for their own screens, but what a typed name *means* is one rule, and a
 * rule that existed twice would eventually give a duplicate a different version number from a fork.
 *
 * This lived on `NetworkEditorStore` while the editor was its only caller. It moved here under the
 * precedent `core/metric-display.periodReadout` and `core/text-entry.ts` set - two features, one
 * fact - and the store re-reads it rather than declaring it, so `ForkRequest` is still the type its
 * `fork()` takes.
 *
 * `leverChanges` becomes `configuration_variant.lever_changes_json`, which is kept so that a
 * metric improvement can be attributed to a lever, and which the comparison view renders
 * as the annotation under each column. Null means the researcher recorded nothing, which is shown
 * differently from an empty annotation.
 */
export interface ForkRequest {
  /** Name for the copy, or null to take the base network's name and the next version number. */
  readonly name: string | null;
  /** The lever note, or null when nothing was recorded. */
  readonly leverChanges: LeverChanges | null;
}

/**
 * The fields a fork dialog collects, resolved into a {@link ForkRequest} (FR-09, FR-26).
 *
 * ## Why a name equal to the base network's resolves to null
 *
 * Because the two dialogs present the same default in opposite ways and must not diverge because of
 * it. The editor's fork prompt leaves the field **empty** with the base name as a placeholder and
 * says "leave empty to keep the same name"; FR-26's duplicate dialog **prefills** it with the base
 * name, which is not a placeholder but the intended way to take the next
 * version number and sort beside it. Untouched, both mean *the same name, next version* - so both
 * resolve to `null` here and the request that leaves the browser is byte-identical.
 *
 * The server agrees either way (`NetworkService.clone` treats a null or blank name as the base
 * name, then takes `findMaxVersion(name) + 1`), so this is not a correction of the API. It is what
 * keeps *one* answer to "what did the researcher ask for" on this side of the wire, where the two
 * dialogs would otherwise send `{}` and `{"name":"Baseline"}` for the same gesture.
 *
 * @param typedName what is in the name field, however it got there
 * @param baseName  the network being forked - its name is the field's meaning when untouched
 * @param note      the lever note; blank records nothing rather than recording an empty annotation
 */
export function forkRequestFrom(typedName: string, baseName: string, note: string): ForkRequest {
  const name = typedName.trim();
  const recorded = note.trim();
  return {
    name: name && name !== baseName.trim() ? name : null,
    leverChanges: recorded ? { note: recorded } : null,
  };
}

/**
 * The JSON body of `POST /networks/{id}/clone`.
 *
 * **Both fields are omitted rather than sent null when unset.** An absent `name` means "take the
 * next version of the same name", and an absent `leverChanges` means the variant carries no
 * annotation - which the comparison view renders differently from an empty one. This is
 * the one place that mapping is made, because `NetworkCloneService` is the one caller of the
 * endpoint.
 */
export function cloneBody(request: ForkRequest): CloneNetworkRequest {
  return {
    ...(request.name ? { name: request.name } : {}),
    ...(request.leverChanges ? { leverChanges: request.leverChanges } : {}),
  };
}
