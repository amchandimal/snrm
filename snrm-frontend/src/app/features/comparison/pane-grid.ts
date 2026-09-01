import { Id } from '../../core/models';

/**
 * The side-by-side window's grid, the cap on how many panes may be in it, and whether those panes
 * open with their metrics showing (FR-25, FR-27).
 *
 * > "the Compare action opens a new window laid out as a grid of panes, one per selected network:
 * > two panes side by side, four as a 2 × 2, the general rule being `columns = ⌈√n⌉` and
 * > `rows = ⌈n / columns⌉`. The selection is capped, because a pane too small to read a node label
 * > in is a pane that shows nothing."
 *
 * Free of Angular, of HTTP and of the DOM, like `radar-geometry.ts` beside it and
 * `simulations/mini-map-layout.ts` one feature across. Four things are pinned here because more than
 * one surface has to agree about each: the arithmetic that decides the shape of the window; the
 * **cap**, which is stated by the FR-23 actions menu *before* the window opens and enforced by the
 * route *after* it; the **default collapse**, which follows the count and must therefore be derived
 * from the same grid rule the count is drawn by; and the **label** of the one control above the grid.
 * `pane-grid.spec.ts` walks every rule below.
 *
 * ## Why ⌈√n⌉ columns rather than a fixed two
 *
 * Because the rule has to answer for every n, and the obvious two are the two a reader will
 * check: 2 is left-and-right, 4 is a 2 × 2. Three is the interesting case - ⌈√3⌉ = 2 columns and
 * ⌈3/2⌉ = 2 rows, so it draws two over one, which is what a reader means by "side by side" for an
 * odd number far better than a 3 × 1 strip of slivers or a 1 × 3 stack nothing is beside.
 *
 * `Math.sqrt` is correctly rounded in IEEE 754, so a perfect square gives its root exactly and
 * `⌈√4⌉` is 2 rather than 3 - the one place this arithmetic could have gone wrong silently.
 *
 * **The rule itself did not change when the cap grew.** Twelve panes take ⌈√12⌉ = 4 columns and
 * ⌈12/4⌉ = 3 rows.
 *
 * ## The cap is twelve, and the earlier judgements were six and ten
 *
 * This module argued at length, and correctly, that six was right: six is the largest n whose grid
 * stays within three columns and **two rows**; a third row is a configuration below the fold, which
 * is the hunting the view exists to replace; and at four columns a node label - 9 user units in a
 * 340-unit box - goes under the size it can be read at. None of that was wrong, and none of it is
 * deleted here. It is **answered** instead, because the argument assumed a pane always carries its
 * metrics, and FR-27 removes that assumption.
 *
 * **What the fourth column and the third row cost**, stated where they are chosen:
 *
 * - *Smaller panes.* Four columns give each pane about a quarter of the window where three gave it
 *   about a third - less than the results dashboard gives its own miniature (`col-xl-4`), which is
 *   the size that drawing is known to read at. The node labels are at the limit rather than
 *   comfortably above it, which is why {@link PANE_LIMIT_SENTENCE} still says a pane too small to
 *   read a label in shows nothing.
 * - *A third row that scrolls.* At seven and above the grid takes a third row, and an expanded pane
 *   is tall enough that the row is below the fold.
 * - *One `NODE_CRITICALITY` suite per pane.* Each pane spends one
 *   `GET /networks/{id}/metrics/topological`, and that metric is one maximum-flow computation **per
 *   node** (FR-04) - the most expensive thing this API computes synchronously. Twelve of
 *   them is why `SideBySideStore` fetches the suites one at a time and not at once.
 *
 * **What pays for it** is FR-27's collapse: a collapsed pane is a title and a miniature, and a wall
 * of shapes is the readable form at this count - with the numbers one click away for the two or
 * three panes a reader is actually weighing. The third row still scrolls; what changed is how much
 * of a pane is above the fold before it does. Hence {@link suitesExpandedByDefault}: the default
 * follows the count, and it is derived from {@link paneGrid} rather than from a number typed beside
 * it, so the hinge and the grid rule cannot drift apart.
 *
 * **Moving the cap from ten to twelve moved nothing else**, because ten and twelve draw the
 * *same grid*: four columns and three rows is what ten already paid for, and that grid has twelve
 * cells. Ten filled ten of them and left the bottom row ragged - a window that looks as though it
 * ran out rather than one that was capped. So neither bullet above changes: no new column, so the
 * labels are no smaller; no new row, so nothing is further down. Only the third does, by two
 * suites. **Thirteen is the first count that takes a fourth row** (⌈√13⌉ = 4, ⌈13/4⌉ = 4), which is
 * that same argument reaching its next limit, and it is what the cap sits against now.
 *
 * The cap is a **cap and not a refusal**: a URL naming more networks draws the first twelve and says
 * which it left out ({@link overCapNote}), because a bookmark that has aged past a limit should
 * still show a reader something.
 */

/**
 * How many panes the window will draw at once. See the module note for why it is twelve -
 * the count that fills the four columns and three rows the cap of ten already took.
 */
export const PANE_LIMIT = 12;

/**
 * Fewest networks the Compare gesture accepts.
 *
 * Two, because one network beside nothing is not a comparison and the by-name selection - the point
 * of the view (FR-25) - has nothing to match against. The *route* is more permissive on purpose: a
 * URL naming one network draws one pane rather than an error, since a bookmark whose other networks
 * were deleted is still a request to look at what is left.
 */
export const PANE_MINIMUM = 2;

/** The shape of the window: how many panes across, and how many down. */
export interface PaneGrid {
  readonly columns: number;
  readonly rows: number;
}

/**
 * The grid for `count` panes: `columns = ⌈√n⌉`, `rows = ⌈n / columns⌉`.
 *
 * Nothing to draw answers `0 × 0` rather than `1 × 0`, so a caller rendering
 * `repeat(columns, 1fr)` writes no track for an empty window instead of one empty one.
 */
export function paneGrid(count: number): PaneGrid {
  const panes = Number.isFinite(count) ? Math.max(0, Math.floor(count)) : 0;
  if (panes === 0) {
    return { columns: 0, rows: 0 };
  }
  const columns = Math.ceil(Math.sqrt(panes));
  return { columns, rows: Math.ceil(panes / columns) };
}

/**
 * How many rows of panes fit on a screen with their metrics showing.
 *
 * Two - the number the six-pane cap was chosen to hold the window to, kept as the thing it always
 * really was: not a count of networks but a count of *rows*, since a row is what does or does not
 * fit above the fold. It is expressed here so the hinge below reads as the old argument applied
 * rather than as a second magic number.
 */
const EXPANDED_ROW_LIMIT = 2;
// If the hinge turns out to be wrong in front of a real screen - a third row of expanded panes that
// does fit, or a second row that does not - this is the one number to move, and the "expanded
// up to six panes" rule moves with it. Nothing else in the application states the threshold.

/**
 * Whether a window of `count` panes opens with every pane's structural suite **expanded** (FR-27).
 *
 * > "The default follows the pane count: expanded up to six panes, collapsed above, so the larger
 * > grids FR-25 now permits open as a wall of shapes rather than a screen the reader must scroll."
 *
 * Derived from {@link paneGrid} rather than compared against a literal six, and that is the whole
 * point of it living here: six *is* the largest count whose grid stays within two rows, so
 * writing it as a number would leave two rules free to drift the next time either moves. Change the
 * grid rule and the hinge follows it; the spec pins that the answer today is still six.
 *
 * A count past the cap is answered on its own terms rather than clamped - {@link parsePaneIds} has
 * already dropped the overage by the time a window has panes to collapse, and a function that
 * silently agreed with a number it was not given would hide the one case where the two disagree.
 */
export function suitesExpandedByDefault(count: number): boolean {
  const grid = paneGrid(count);
  return grid.rows <= EXPANDED_ROW_LIMIT;
}

/**
 * The largest pane count that opens expanded - six today, and never typed as six.
 *
 * Only the prose needs the number; {@link suitesExpandedByDefault} is the rule. Deriving it costs
 * one loop to the cap at module load and removes the way this sentence would otherwise come to
 * describe a threshold the code no longer has - which it would already have done twice, since the
 * cap has moved twice (six → ten → twelve) and this number has not moved at all.
 */
export const SUITE_EXPANDED_LIMIT = largestExpandedCount();

function largestExpandedCount(): number {
  let largest = 0;
  for (let count = 1; count <= PANE_LIMIT; count += 1) {
    if (suitesExpandedByDefault(count)) {
      largest = count;
    }
  }
  return largest;
}

/**
 * The cap, in the words both the menu and the window use. One sentence, one place.
 *
 * It states the limit, what the limit costs, and what pays for it - because the reader who meets
 * this sentence in the actions menu is deciding whether to tick a thirteenth row, and "up to twelve"
 * with no account of what twelve looks like is a number they have no way to weigh.
 */
export const PANE_LIMIT_SENTENCE =
  `Up to ${PANE_LIMIT} networks at once - ${PANE_LIMIT} fills a grid of four columns and three rows, ` +
  'so the panes are small and the last row scrolls, and a thirteenth would take a fourth row. Above ' +
  `${SUITE_EXPANDED_LIMIT} panes each one opens as a title and a miniature, with its structural ` +
  'metrics collapsed and one click away.';

/**
 * Why a window of this size opened the way it did - the sentence beside the Collapse all control.
 *
 * A count of one is reachable (a link whose other networks were deleted), so it is worded rather
 * than left reading "1 panes" - the same care `overCapNote` takes over its own singulars.
 */
export function suiteDefaultSentence(count: number): string {
  const panes = whole(count);
  const subject = panes === 1 ? 'One pane fits' : `${panes} panes fit`;
  return suitesExpandedByDefault(panes)
    ? `${subject} in ${EXPANDED_ROW_LIMIT} rows, so each one opens showing its structural metrics.`
    : `${panes} panes take more than ${EXPANDED_ROW_LIMIT} rows, so each opens as a title and a ` +
        'miniature - shapes beside shapes, with the numbers a click away.';
}

/** What the one control above the grid says, and what pressing it will do (FR-27). */
export interface CollapseAllControl {
  /** `Collapse all` or `Expand all` - always the state pressing it *produces*. */
  readonly label: string;
  /** What it does to **every** pane: true collapses them all, false expands them all. */
  readonly collapse: boolean;
  /** The sentence the control carries, saying what will happen and what will not. */
  readonly hint: string;
}

/**
 * The label rule for the window's one collapse control (FR-27).
 *
 * > "one control above the grid collapses or expands them all - labelled **Collapse all**, and
 * > **Expand all** once every pane is collapsed, always naming the state it will produce."
 *
 * Two rules, and the second is the one worth pinning:
 *
 * 1. The label names the state pressing it **produces**, never the state the window is in. A button
 *    reading "Expanded" would be a status; this is a control.
 * 2. **A mixed window reads "Collapse all".** Pressing it must always take *every* pane somewhere -
 *    a control that collapsed the expanded half and left the collapsed half alone is doing the same
 *    thing, but a control that flipped each pane would leave the reader working out which half
 *    moved, and a reader who has to work that out has been handed a puzzle instead of a view. So the
 *    mixed case resolves toward collapse: it is the state the larger grids are for, and one more
 *    press gets back to the other one.
 *
 * Pure and specced rather than inlined in the template, because it is a rule about wording that two
 * later readings ("why does it say Collapse when four are already collapsed?") would otherwise have
 * to re-derive from a ternary - the argument `network-selection.ts` makes for its own sentences.
 *
 * A window with no collapsible panes answers "Collapse all" and is not rendered by its caller; the
 * function still answers, because a pure function that threw for the empty case would be a second
 * thing the caller has to know.
 */
export function collapseAllControl(panes: number, collapsed: number): CollapseAllControl {
  const total = whole(panes);
  const already = Math.min(whole(collapsed), total);

  if (total > 0 && already === total) {
    return {
      label: 'Expand all',
      collapse: false,
      hint:
        'Every pane is collapsed. This opens all of their structural metrics - nothing is ' +
        're-read, because each pane already holds its suite.',
    };
  }
  if (already === 0) {
    return {
      label: 'Collapse all',
      collapse: true,
      hint:
        'Collapse every pane to its title and its miniature. The metrics stay loaded, so ' +
        'expanding a pane again costs no request.',
    };
  }
  return {
    label: 'Collapse all',
    collapse: true,
    hint:
      `${already} of ${total} panes are already collapsed; this collapses the rest, so one press ` +
      'always takes every pane to the same place.',
  };
}

/** A count as a whole number of things, however it arrived. */
function whole(count: number): number {
  return Number.isFinite(count) ? Math.max(0, Math.floor(count)) : 0;
}

/** What the query string asked for, sorted into what will be drawn and what was not usable. */
export interface PaneSelection {
  /** The networks to draw, in the order the URL named them, capped at {@link PANE_LIMIT}. */
  readonly ids: readonly Id[];
  /** Valid ids past the cap. Named on screen rather than dropped in silence. */
  readonly dropped: readonly Id[];
  /** Tokens that were not network ids at all, verbatim, so the sentence can quote them. */
  readonly malformed: readonly string[];
  /** How many repeats were folded away - the same network twice is one pane, not two. */
  readonly duplicates: number;
}

/** An empty ask - no `ids` at all. Its own constant so the identity is stable across reads. */
const NOTHING_SELECTED: PaneSelection = { ids: [], dropped: [], malformed: [], duplicates: 0 };

/**
 * Read the `?ids=` of the side-by-side route (FR-25).
 *
 * > "It must be a real URL that survives a reload and can be bookmarked - the ids are in the URL,
 * > not passed in memory."
 *
 * Which is exactly why this is a parser rather than a cast. A URL a user may edit, bookmark, mail to
 * a supervisor and open six months later is untrusted input in the ordinary sense: it can carry a
 * network that has since been deleted, a token that is not a number, the same id twice, or more ids
 * than the window will draw. Every one of those has an answer here, and none of them is an
 * exception.
 *
 * **Order is the URL's**, not the ids' - the reader arranged the panes when they ticked the rows,
 * and re-sorting them would rearrange somebody's comparison on reload.
 *
 * Accepts both the comma-separated form this application writes (`?ids=3,4,5`) and the repeated form
 * a router may hand back (`?ids=3&ids=4`), because the second costs one `Array.isArray` and a
 * bookmark written by hand is as likely to use it.
 */
export function parsePaneIds(raw: string | readonly string[] | null | undefined): PaneSelection {
  if (raw === null || raw === undefined) {
    return NOTHING_SELECTED;
  }
  // `typeof` rather than `Array.isArray`, which does not narrow a `readonly` array cleanly.
  const entries: readonly string[] = typeof raw === 'string' ? [raw] : raw;
  const tokens = entries
    .flatMap((entry) => entry.split(/[,\s]+/))
    .map((token) => token.trim())
    .filter((token) => token.length > 0);

  const ids: Id[] = [];
  const dropped: Id[] = [];
  const malformed: string[] = [];
  const seen = new Set<Id>();
  let duplicates = 0;

  for (const token of tokens) {
    // `Number` rather than `parseInt`, which reads "3abc" as 3 - a malformed token must not become
    // a request for somebody else's network.
    const value = Number(token);
    if (!Number.isInteger(value) || value <= 0) {
      malformed.push(token);
      continue;
    }
    if (seen.has(value)) {
      duplicates += 1;
      continue;
    }
    seen.add(value);
    if (ids.length < PANE_LIMIT) {
      ids.push(value);
    } else {
      dropped.push(value);
    }
  }

  return { ids, dropped, malformed, duplicates };
}

/** The `?ids=` this application writes: `3,4,5`. The form {@link parsePaneIds} round-trips. */
export function paneIdsParam(ids: readonly Id[]): string {
  return ids.join(',');
}

/**
 * What the window says about an ask it could not honour in full, or null.
 *
 * One sentence covering all three ways a URL can be more than the view will draw, because they
 * arrive together (a hand-edited link) as often as alone, and three stacked banners for one bad
 * paste is three times the noise for one fact.
 */
export function overCapNote(selection: PaneSelection): string | null {
  const parts: string[] = [];
  if (selection.dropped.length > 0) {
    parts.push(
      `${selection.dropped.length} more ${selection.dropped.length === 1 ? 'network was' : 'networks were'} ` +
        `named in the link and ${selection.dropped.length === 1 ? 'is' : 'are'} not drawn ` +
        `(${selection.dropped.map((id) => `#${id}`).join(', ')}). ${PANE_LIMIT_SENTENCE}`,
    );
  }
  if (selection.duplicates > 0) {
    parts.push(
      `${selection.duplicates} repeated ${selection.duplicates === 1 ? 'id was' : 'ids were'} ` +
        'folded away - a network appears once however many times the link names it.',
    );
  }
  if (selection.malformed.length > 0) {
    parts.push(
      `${selection.malformed.length === 1 ? 'One entry in the link is' : `${selection.malformed.length} entries in the link are`} ` +
        `not a network id and ${selection.malformed.length === 1 ? 'was' : 'were'} ignored ` +
        `(${selection.malformed.map((token) => `“${token}”`).join(', ')}).`,
    );
  }
  return parts.length ? parts.join(' ') : null;
}

/**
 * Why the Compare action is not offered for a selection, or null (FR-23's menu, FR-25's cap).
 *
 * Here rather than in the menu component because FR-25 asks for the cap to be stated **where the
 * action is offered**, not only after the window has opened - so the number and the reason are read
 * by two surfaces, and two copies of a limit is the way a limit comes to differ from itself. The
 * menu renders this string; the window enforces the same {@link PANE_LIMIT} through
 * {@link parsePaneIds}.
 */
export function compareBlocker(count: number): string | null {
  const selected = whole(count);
  if (selected < PANE_MINIMUM) {
    return (
      `Tick at least ${PANE_MINIMUM} networks. One network beside nothing is not a comparison, and ` +
      'the shared by-name selection - clicking a node in one pane to find it in the others - has ' +
      'nothing to match against.'
    );
  }
  if (selected > PANE_LIMIT) {
    return `${PANE_LIMIT_SENTENCE} Untick ${selected - PANE_LIMIT} to open the rest side by side.`;
  }
  return null;
}
