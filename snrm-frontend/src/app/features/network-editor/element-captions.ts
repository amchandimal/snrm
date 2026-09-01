import { Id } from '../../core/models';

/**
 * Captions on a node and on an arc: what draws, what an edit means, and where the second line sits
 * (FR-30).
 *
 * Pure, free of Angular and of Cytoscape, and specced - for the reason `disruption-overlay.ts`,
 * `playback-channels.ts` and `run-history.ts` are: the canvas itself is deliberately not
 * unit-tested here (headless canvas tests buy little and flake often), so everything about this
 * feature that can be arithmetic or a rule rather than a rendering call lives in this file and is
 * pinned. `graph-canvas` is left with the part a test could only re-describe: adding, moving and
 * removing Cytoscape elements.
 *
 * ## Three rules, in the order they bite
 *
 * **An empty caption draws nothing, whatever the checkbox says** ({@link drawnCaption}). The rule
 * is absolute, and it is what makes the checkbox a control over annotation that *exists*
 * rather than a way to reserve space on the canvas.
 *
 * **A caption is the one field the editor's bulk PATCH can clear** ({@link previousAttributeValue}).
 * A PATCH cannot express "set to nothing", as this folder's README also notes - omitted means
 * "leave alone", which is exactly what bulk editing needs - so every other clear in this editor
 * routes through `ReplaceNodeCommand` / `ReplaceLinkCommand` and the full-replacement PUT. The
 * backend carved out one exception for FR-30: on `PATCH /networks/{id}/nodes` and `/links`, a
 * *present but empty* `caption` clears it (`com.snrm.network.Captions`, and the Javadoc on
 * `NodePatch.caption`). So emptying the field here is an ordinary edit on the ordinary path, and the
 * clear is undoable in the same debounced batch as the edit that wrote it - where a PUT would have
 * made the single caption edit that removes something take a different endpoint from every caption
 * edit that adds something, and replace the element's whole attribute set on the way.
 *
 * **The caption is a second drawn thing, not a style on the first** ({@link nodeCaptionAnchor},
 * {@link linkCaptionAnchor}). Cytoscape draws one label per element at one font size - which is
 * already why an arc's label carries its lead time and nothing else (`edgeLabel`) - so a smaller,
 * quieter line beneath that label needs an element of its own. The arithmetic that decides where
 * "beneath" is, is here.
 */

// ------------------------------------------------------------------ the drawing rule

/** `caption VARCHAR(200)` in `V10__element_captions.sql`, and the `@Size` on every request DTO. */
export const CAPTION_MAX_LENGTH = 200;

/**
 * The caption the canvas should draw for one element, or null when it draws nothing.
 *
 * Two ways to draw nothing and they are deliberately not the same fact: the flag is off, or there is
 * no caption to hide. *An empty caption draws nothing, whatever the checkbox says*, so
 * the emptiness is checked whichever way the flag points.
 *
 * The trim is belt and braces rather than normalisation - the backend trims on every write path and
 * stores a blank one as null, so a caption reaching here is already trimmed or already null. It is
 * here because the *store* holds the empty string for the moment between an edit clearing a caption
 * and the flush that confirms it (`EditNodesCommand` writes the patch it sent into the local copy),
 * and a canvas that drew a blank second line for two seconds would be a defect nobody could
 * reproduce on reload.
 */
export function drawnCaption(caption: string | null, captionVisible: boolean): string | null {
  if (!captionVisible || caption === null) {
    return null;
  }
  const trimmed = caption.trim();
  return trimmed.length === 0 ? null : trimmed;
}

// --------------------------------------------------------------------- the edit rules

/** The caption field's name, as `NodeAttributes` / `LinkAttributes` spell it. */
export const CAPTION_FIELD = 'caption';

/**
 * What a caption edit sends to remove one: an empty string, not an omission.
 *
 * Named rather than written as `''` at three call sites, because the empty string is a *value* here
 * - the one place in this editor where it is a write rather than a refusal - and an unexplained
 * `''` in a patch reads as a bug on the way past.
 */
export const CLEARED_CAPTION = '';

/**
 * Whether committing `typed` against the caption an element currently holds is a real change.
 *
 * The panel asks before it raises a command, so that tabbing through an empty caption field on an
 * uncaptioned element costs neither a PATCH nor an undo step. `sameFieldValue` in `core/time-units`
 * cannot answer it: null and `''` are the same *state* for a caption and different values for
 * everything else, which is exactly the asymmetry this feature introduced.
 */
export function captionChanged(current: string | null, typed: string): boolean {
  return (current === null ? CLEARED_CAPTION : current) !== typed.trim();
}

/**
 * The value that puts one attribute back where it was, or `undefined` where a PATCH cannot say it.
 *
 * The store records a `before` for every field an edit touches so the command can be reverted. For
 * every field but one, a value that *was* null has no undo the bulk PATCH can express - omitted
 * means "leave alone" - so the field is left out of `before` and undoing "set a region on a node
 * that had none" leaves the region, which is the limit `EditNodesCommand` has always documented.
 *
 * **A caption is the exception, because the backend made it one.** A previously-absent caption
 * undoes to {@link CLEARED_CAPTION}, which the PATCH reads as *clear it* - so "typed a caption,
 * Ctrl-Z" removes the caption rather than reporting success and leaving it on the canvas.
 *
 * The `current !== null` test is why `core/api-nulls.normaliseNode` had to learn about `caption`: an
 * *undefined* caption takes the first branch and returns undefined, which the caller omits - the
 * silent, inverted failure that module exists to end.
 */
export function previousAttributeValue(field: string, current: unknown): unknown {
  if (current !== null) {
    return current;
  }
  return field === CAPTION_FIELD ? CLEARED_CAPTION : undefined;
}

// ------------------------------------------------------- the companion element's identity

/**
 * Prefix of every caption element's id, and the class each one carries.
 *
 * It must parse as neither a node id (`n12`) nor a link id (`l30`), for the reason
 * `HANDLE_ID_PREFIX` must not: `graph-canvas.render()` removes **only** ids it recognises as one of
 * those two, precisely so a render firing mid-gesture cannot delete the drag handle or edgehandles'
 * ghost out from under the pointer. A caption element is managed by its own pass on exactly the same
 * terms - that pass removes only ids {@link isCaptionElementId} claims, so it can never reach a
 * handle, a ghost, a preview edge or a real element either.
 */
export const CAPTION_ID_PREFIX = 'snrm-caption-';

/** The class every caption element carries. Written together with the id, never on its own. */
export const CAPTION_CLASS = 'snrm-caption';

/** The caption element belonging to the element drawn as `ownerElementId` (`n12`, `l30`). */
export function captionElementId(ownerElementId: string): string {
  return `${CAPTION_ID_PREFIX}${ownerElementId}`;
}

/** The element a caption element describes, or null when the id is not a caption's. */
export function captionOwnerElementId(elementId: string | undefined | null): string | null {
  if (!elementId || !elementId.startsWith(CAPTION_ID_PREFIX)) {
    return null;
  }
  const owner = elementId.slice(CAPTION_ID_PREFIX.length);
  return owner.length === 0 ? null : owner;
}

/** True for a caption element and for nothing else on the canvas. */
export function isCaptionElementId(elementId: string | undefined | null): boolean {
  return captionOwnerElementId(elementId) !== null;
}

/** One caption the canvas should be drawing: which element it belongs to, and what it says. */
export interface CaptionElement {
  /** Cytoscape id of the caption element itself. */
  readonly id: string;
  /** Cytoscape id of the node or arc it sits under. */
  readonly ownerElementId: string;
  readonly text: string;
}

/** A node or a link, as far as this module needs to know. */
interface Captioned {
  readonly id: Id;
  readonly caption: string | null;
  readonly captionVisible: boolean;
}

/**
 * Every caption the canvas should be drawing, keyed by its element id.
 *
 * The whole desired state in one map, so the canvas pass is a diff against what is on screen rather
 * than a sequence of add/remove decisions taken one element at a time - the same shape `render`
 * takes against the store, and the reason a caption cannot be stranded by an element being deleted
 * while the panel is open.
 */
export function captionElements(
  nodes: Iterable<Captioned>,
  links: Iterable<Captioned>,
  nodeElementId: (id: Id) => string,
  linkElementId: (id: Id) => string,
): ReadonlyMap<string, CaptionElement> {
  const wanted = new Map<string, CaptionElement>();
  const add = (ownerElementId: string, element: Captioned): void => {
    const text = drawnCaption(element.caption, element.captionVisible);
    if (text === null) {
      return;
    }
    const id = captionElementId(ownerElementId);
    wanted.set(id, { id, ownerElementId, text });
  };
  for (const node of nodes) {
    add(nodeElementId(node.id), node);
  }
  for (const link of links) {
    add(linkElementId(link.id), link);
  }
  return wanted;
}

// ------------------------------------------------------------------------- the geometry

/**
 * The canvas type metrics this arithmetic reads, and every one of them is a number stated in
 * `graph-canvas.canvasStyle()`. They are duplicated here rather than derived from Cytoscape because
 * a stylesheet is not queryable for the size of a label that has not been laid out yet, and because
 * an offset that has to be *right* is worth having a spec pin. `element-captions.spec.ts` states the
 * pairing, so a font size changed on one side without the other fails a test rather than nudging
 * every caption a pixel into its name.
 */

/** `font-size` on the `node` rule - the size of an element's own name. */
export const NAME_FONT_SIZE = 11;

/** `text-margin-y` on the `node` rule: the gap between a node's bottom edge and its name. */
export const NAME_MARGIN_Y = 6;

/** `font-size` on the `edge` rule - the size of an arc's lead-time label. */
export const EDGE_LABEL_FONT_SIZE = 10;

/**
 * `font-size` on the caption rule.
 *
 * **Smaller than {@link NAME_FONT_SIZE}, and that is a requirement rather than a taste**: the rule
 * is that the caption never competes with the name - an element is identified by its name
 * everywhere else in the tool (`uq_node`, the archive's `ElementRef`, FR-25's by-name matching), and
 * a second line at the same weight would put two identities on one element.
 */
export const CAPTION_FONT_SIZE = 9;

/** `text-background-padding` on both label rules, counted once per edge of the box. */
export const LABEL_PADDING = 2;

/** Clear air between a label's background box and the caption's. */
export const CAPTION_GAP = 2;

/** The drawn height of a label's background box at one font size. */
function labelBand(fontSize: number): number {
  return fontSize + 2 * LABEL_PADDING;
}

/** A point in Cytoscape's model coordinates. */
export interface CaptionPoint {
  readonly x: number;
  readonly y: number;
}

/**
 * How far below a node's centre its caption's anchor sits.
 *
 * Reads a node's **outer** height rather than the constant, because the criticality
 * encoding writes a per-node diameter between 30 and 74 px: a caption pinned to the default 46
 * would sit inside the most critical nodes and float away from the least. That is why
 * `applyCriticality` re-anchors - the number below is a function of a size that changes without the
 * network changing at all.
 *
 * The anchor is the **top** of the caption's box, not its centre, so the caption element states
 * `text-valign: bottom` and a wrapped caption grows downward, away from the name, instead of
 * climbing back into it a line at a time.
 */
export function nodeCaptionOffsetY(outerHeight: number): number {
  return outerHeight / 2 + NAME_MARGIN_Y + labelBand(NAME_FONT_SIZE) + CAPTION_GAP;
}

/**
 * How far below an arc's midpoint its caption's anchor sits.
 *
 * An arc's own label is centred *on* the midpoint (`text-valign` defaults to centre on an edge), so
 * clearing it means half its box plus the gap. The caption is deliberately **not** rotated with the
 * arc the way that label is: `edge-text-rotation: autorotate` earns its keep on a one-word lead time
 * in a dense echelon, and turns a sentence into something the reader tilts their head at.
 */
export function linkCaptionOffsetY(): number {
  return labelBand(EDGE_LABEL_FONT_SIZE) / 2 + CAPTION_GAP;
}

/** Where a node's caption element goes, from the node's own centre and drawn height. */
export function nodeCaptionAnchor(centre: CaptionPoint, outerHeight: number): CaptionPoint {
  return { x: centre.x, y: centre.y + nodeCaptionOffsetY(outerHeight) };
}

/** Where an arc's caption element goes, from the midpoint Cytoscape reports for the arc. */
export function linkCaptionAnchor(midpoint: CaptionPoint): CaptionPoint {
  return { x: midpoint.x, y: midpoint.y + linkCaptionOffsetY() };
}
