import { DisruptionEvent, DisruptionTargetType, Id, Network } from '../../core/models';
import { TimelineBar, placeBar } from '../scenario-builder/timeline';

/**
 * Turning a scenario's events into marks on the canvas (FR-16).
 *
 * > "Events are drawn over the network they apply to. A targeted element carries a disruption badge,
 * > with its window and severity on hover."
 *
 * Free of Angular and of HTTP, like `scenario-builder/timeline.ts` and `echelon-rules.ts`: this is
 * grouping and phrasing, and pure functions are the version of it that can be checked.
 *
 * ## Three rules shape it
 *
 * **A bar and a badge are the same placement.** The window is converted onto the network's clock by
 * {@link placeBar} - the timeline's own function, not a copy of it - so an event cannot read
 * "periods 28–38" on one surface and "periods 28–39" on the other. That is the whole reason this
 * imports from another feature folder rather than re-deriving four lines of arithmetic.
 *
 * **A region is resolved by the server or not at all.** A REGION event names a `node.region` tag
 * and this module never looks at `node.region` to expand it. The caller passes what
 * `GET /networks/{id}/region-nodes` answered; a tag with no entry is left unresolved rather than
 * quietly filtered client-side, because a second implementation of the resolution is free to
 * disagree with the one a simulation run will use.
 *
 * **An event that resolves to nothing is not an error.** A scenario is project-scoped and outlives
 * the network its events were authored against, so a node id from another variant is a
 * legitimate row of a legitimate scenario. It comes back in {@link DisruptionOverlay.unresolved} for
 * the panel to say so, rather than being dropped - a scenario whose event count exceeds what the
 * canvas shows would look like a defect in whichever the reader trusts less.
 *
 * ## The underlay has one owner at a time (FR-16 + FR-18)
 *
 * These marks are drawn as the Cytoscape **underlay**, and so is visual playback's availability
 * tint - deliberately, because they are two readings of one thing and must not be two halos. The
 * badge here is *authoring* state: **this element is struck somewhere in the horizon**, true for as
 * long as the scenario is open, whether or not a run has ever been submitted. Playback's tint is
 * *this period*: `0.35 × (1 − availability)` on the period the clock is showing, so an element goes
 * dark as the window opens and clears as it closes.
 *
 * While a run is playing, the second one wins: `graph-canvas` puts its `snrm-playback` rules after
 * these in the stylesheet, so the static halos go quiet **without any of the data here being
 * touched**, and they come straight back when playback stops and the class is removed. Nothing in
 * this module knows about playback, and nothing in playback writes `disrupted` - the handover is
 * one line of selector order, stated at both ends so neither can be changed alone.
 */

/** One event as it reaches one element of the canvas. */
export interface MarkedEvent {
  readonly event: DisruptionEvent;
  /** The region tag it arrived through, or null when the element is named by id. */
  readonly viaRegion: string | null;
  /** Where its window sits on this network's clock - the placement the timeline draws. */
  readonly bar: TimelineBar;
  /** The hover line: `80% · 4 wk → 10 d · periods 28–38 of 52`. */
  readonly line: string;
}

/** Everything the canvas needs in order to draw one targeted element. */
export interface DisruptionMark {
  /** Earliest window first, as the timeline orders bars within a row. */
  readonly events: readonly MarkedEvent[];
  /** The hardest hit striking this element, in [0,1]. */
  readonly maxSeverity: number;
  /** True when every event reaching it does so through a region tag rather than by id. */
  readonly regionOnly: boolean;
  /** True when at least one window ends after the horizon. */
  readonly exceedsHorizon: boolean;
}

/** The marks of one scenario against one network. */
export interface DisruptionOverlay {
  readonly nodes: ReadonlyMap<Id, DisruptionMark>;
  readonly links: ReadonlyMap<Id, DisruptionMark>;
  /**
   * Events that strike nothing on this canvas: a node or link id this network does not have, or a
   * region tag that resolved to no node - or has not been resolved yet.
   */
  readonly unresolved: readonly DisruptionEvent[];
}

/** What the network holds, and what the server said each region tag covers. */
export interface OverlayResolution {
  readonly nodeIds: ReadonlySet<Id>;
  readonly linkIds: ReadonlySet<Id>;
  /**
   * `region` → the node ids carrying it, **as the server resolved them**.
   *
   * A tag with no entry has not been answered - not asked yet, or asked and failed. An entry with an
   * empty array is the server's answer that nothing in this network carries it, which is a different
   * thing and is why the two are not collapsed.
   */
  readonly regionNodes: ReadonlyMap<string, readonly Id[]>;
}

/** Groups a scenario's events onto the elements of one network. */
export function buildOverlay(
  events: readonly DisruptionEvent[],
  network: Network,
  resolution: OverlayResolution,
): DisruptionOverlay {
  const nodes = new Map<Id, MarkedEvent[]>();
  const links = new Map<Id, MarkedEvent[]>();
  const unresolved: DisruptionEvent[] = [];

  for (const event of events) {
    const bar = placeBar(event, network);

    if (event.targetType === DisruptionTargetType.REGION) {
      const tag = event.targetRegion ?? '';
      const matched = resolution.regionNodes.get(tag);
      if (!matched?.length) {
        unresolved.push(event);
        continue;
      }
      const marked = { event, viaRegion: tag, bar, line: eventLine(event, bar, tag) };
      // Only nodes the canvas actually holds: the resolution comes from the server and the store's
      // node map may be a moment behind it - a node deleted since is not something to draw a halo
      // around.
      const drawn = matched.filter((nodeId) => resolution.nodeIds.has(nodeId));
      if (!drawn.length) {
        unresolved.push(event);
        continue;
      }
      for (const nodeId of drawn) {
        push(nodes, nodeId, marked);
      }
      continue;
    }

    const targetId = event.targetId;
    const into = event.targetType === DisruptionTargetType.NODE ? nodes : links;
    const present =
      event.targetType === DisruptionTargetType.NODE ? resolution.nodeIds : resolution.linkIds;
    if (targetId === null || !present.has(targetId)) {
      unresolved.push(event);
      continue;
    }
    push(into, targetId, { event, viaRegion: null, bar, line: eventLine(event, bar, null) });
  }

  return {
    nodes: summarise(nodes),
    links: summarise(links),
    unresolved,
  };
}

/**
 * The hover line for one event.
 *
 * Severity first, because it is the thing that decides whether the halo matters at all. Then the
 * declared window verbatim and what it becomes on this clock - the same pair the timeline puts on a
 * bar and in its tooltip, for the same reason: the researcher typed one and the engine runs the
 * other.
 *
 * Probability appears only when it is not 1. A deterministic event is the common case, and "p 100%"
 * on every line would bury the one line where it is 60%.
 */
export function eventLine(
  event: DisruptionEvent,
  bar: TimelineBar,
  viaRegion: string | null,
): string {
  const parts = [`${percent(event.severity)}% · ${bar.label}`, bar.periodLabel];
  if (event.probability < 1) {
    parts.push(`p ${percent(event.probability)}%`);
  }
  if (viaRegion) {
    parts.push(`via ${viaRegion}`);
  }
  return parts.join(' · ');
}

/** A [0,1] fraction as a whole percentage - how severity and probability read in the UI. */
export function percent(fraction: number): number {
  return Math.round(fraction * 100);
}

function push(into: Map<Id, MarkedEvent[]>, id: Id, marked: MarkedEvent): void {
  const existing = into.get(id);
  if (existing) {
    existing.push(marked);
    return;
  }
  into.set(id, [marked]);
}

function summarise(collected: ReadonlyMap<Id, MarkedEvent[]>): ReadonlyMap<Id, DisruptionMark> {
  const marks = new Map<Id, DisruptionMark>();
  for (const [id, marked] of collected) {
    const events = [...marked].sort(
      (a, b) => a.bar.startPeriod - b.bar.startPeriod || a.event.id - b.event.id,
    );
    marks.set(id, {
      events,
      maxSeverity: events.reduce((worst, entry) => Math.max(worst, entry.event.severity), 0),
      regionOnly: events.every((entry) => entry.viaRegion !== null),
      exceedsHorizon: events.some((entry) => entry.bar.exceedsHorizon),
    });
  }
  return marks;
}
