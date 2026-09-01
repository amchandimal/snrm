import {
  DisruptionEvent,
  DisruptionTargetType,
  Id,
  Network,
  NetworkLink,
  NetworkNode,
} from '../../core/models';
import { durationInPeriods, formatDuration } from '../../core/time-units';

/**
 * Laying a scenario's events out as a Gantt chart.
 *
 * > "Scenario builder - timeline: rows are targeted nodes/links/regions, bars are events
 * > (start/duration), with severity and recovery profile per bar."
 *
 * Free of Angular and of HTTP, like `core/time-units.ts` and `network-editor/echelon-rules.ts`: this
 * is geometry and grouping, and pure functions are the version of it that can be checked.
 *
 * ## The one rule that shapes everything here
 *
 * **An event's timing is a pair of real durations; a bar's position is a number of periods.** The
 * event says "starts 4 weeks in, lasts 10 days" - it does not say "period 28", because a
 * scenario is project-scoped and the variants it will be replayed against need not share a period
 * length. So every bar is *positioned* by {@link durationInPeriods} against the network the
 * user is currently looking at, and *labelled* with what the user actually typed. Change the network
 * in the picker and the bars move; the labels do not.
 *
 * That is also why {@link TimelineBar} carries both numbers. A component that had only the periods
 * could not write the label, and one that had only the durations could not draw the bar.
 */

/** One row of the chart: something a scenario's events point at. */
export interface TimelineTarget {
  /** Stable identity for `@for` tracking and for grouping events. See {@link targetKey}. */
  readonly key: string;
  readonly targetType: DisruptionTargetType;
  /** Node or link id, or null for a region row. */
  readonly targetId: Id | null;
  /** Region tag, or null for a node or link row. */
  readonly targetRegion: string | null;
  /** What the row header shows: a node's name, a link's endpoints, a region tag. */
  readonly label: string;
  /** The smaller second line: the echelon, "link", or how many nodes the region covers. */
  readonly sublabel: string;
  /**
   * True when the target does not exist in the network currently selected.
   *
   * Not an error - a scenario is project-scoped, so it legitimately outlives the network its events
   * were authored against. The row is drawn either way and says so, because silently
   * hiding a bar would make the timeline disagree with `eventCount`.
   */
  readonly unresolved: boolean;
}

/** One event, placed on one row. */
export interface TimelineBar {
  readonly event: DisruptionEvent;
  /** The row this bar sits on. */
  readonly targetKey: string;
  /** Where the event fires, on the selected network's clock. */
  readonly startPeriod: number;
  /** How many periods it lasts, as the engine will discretise it. */
  readonly durationPeriods: number;
  /** `startPeriod + durationPeriods` - the first period after the event. */
  readonly endPeriod: number;
  /** Left edge as a percentage of the horizon. */
  readonly leftPercent: number;
  /** Width as a percentage of the horizon; never zero, so a sub-period event stays clickable. */
  readonly widthPercent: number;
  /** The declared timing, verbatim - `4 wk → 10 d`. Never the period count. */
  readonly label: string;
  /** What the bar becomes on this clock - `periods 28–38 of 52`. For the tooltip. */
  readonly periodLabel: string;
  /**
   * True when the window ends after the horizon.
   *
   * The API refuses to *store* such an event against this network (`EVENT_EXCEEDS_HORIZON`), but a
   * scenario written against a longer-horizon variant can still be viewed here, so
   * the timeline has to be able to draw one.
   */
  readonly exceedsHorizon: boolean;
}

/** A row and the bars on it. */
export interface TimelineRow {
  readonly target: TimelineTarget;
  readonly bars: readonly TimelineBar[];
}

/** Smallest width a bar is drawn at, in percent - enough to stay visible and clickable. */
const MIN_BAR_PERCENT = 1.2;

/**
 * Identity of a target, used both to group events into rows and to track them in the template.
 *
 * A region key includes the tag rather than an id, because a region has none - it is a
 * `node.region` string and nothing else.
 */
export function targetKey(
  targetType: DisruptionTargetType,
  targetId: Id | null | undefined,
  targetRegion: string | null | undefined,
): string {
  return targetType === DisruptionTargetType.REGION
    ? `REGION:${targetRegion ?? ''}`
    : `${targetType}:${targetId ?? ''}`;
}

/** {@link targetKey} for an event. */
export function eventTargetKey(event: DisruptionEvent): string {
  return targetKey(event.targetType, event.targetId, event.targetRegion);
}

/** What the network offers as rows, so a target can be named and its existence checked. */
export interface TargetCatalogue {
  readonly nodes: readonly NetworkNode[];
  readonly links: readonly NetworkLink[];
  /** Region tags in use, with their node counts. */
  readonly regions: readonly { readonly region: string; readonly nodeCount: number }[];
}

/**
 * Groups a scenario's events into rows and places each bar.
 *
 * Rows come out in a fixed order - nodes, then links, then regions, each alphabetically - rather
 * than in event order. A researcher reads down the same list every time they open the scenario, and
 * a row that jumped because a bar was dragged earlier would make the chart harder to compare against
 * itself.
 */
export function buildTimeline(
  events: readonly DisruptionEvent[],
  network: Network,
  catalogue: TargetCatalogue,
): readonly TimelineRow[] {
  const rows = new Map<string, { target: TimelineTarget; bars: TimelineBar[] }>();

  for (const event of events) {
    const key = eventTargetKey(event);
    let row = rows.get(key);
    if (!row) {
      row = { target: describeTarget(event, catalogue), bars: [] };
      rows.set(key, row);
    }
    row.bars.push(placeBar(event, network));
  }

  return [...rows.values()]
    .sort((a, b) => compareTargets(a.target, b.target))
    .map((row) => ({
      target: row.target,
      // Within a row, earliest first - the reading order of a Gantt chart.
      bars: [...row.bars].sort((a, b) => a.startPeriod - b.startPeriod || a.event.id - b.event.id),
    }));
}

/** Places one event on the selected network's clock. */
export function placeBar(event: DisruptionEvent, network: Network): TimelineBar {
  const { periodLength, roundingPolicy, horizonPeriods } = network;
  const startPeriod = durationInPeriods(event.startOffset, periodLength, roundingPolicy);
  const durationPeriods = durationInPeriods(event.duration, periodLength, roundingPolicy);
  const endPeriod = startPeriod + durationPeriods;
  const span = Math.max(horizonPeriods, 1);
  const leftPercent = clampPercent((startPeriod / span) * 100);

  return {
    event,
    targetKey: eventTargetKey(event),
    startPeriod,
    durationPeriods,
    endPeriod,
    leftPercent,
    // A window that rounds to zero periods still gets a sliver: the event exists, and an invisible
    // bar would be an event the user cannot select to fix. The resolution banner is what tells them
    // the duration vanished.
    widthPercent: clampPercent(
      Math.max((durationPeriods / span) * 100, MIN_BAR_PERCENT),
      100 - leftPercent,
    ),
    label: `${formatDuration(event.startOffset)} → ${formatDuration(event.duration)}`,
    periodLabel: periodRange(startPeriod, endPeriod, horizonPeriods),
    exceedsHorizon: endPeriod > horizonPeriods,
  };
}

/** `periods 28–38 of 52` - what the declared durations become on this clock. */
export function periodRange(
  startPeriod: number,
  endPeriod: number,
  horizonPeriods: number,
): string {
  return `periods ${startPeriod}–${endPeriod} of ${horizonPeriods}`;
}

/** Names a target from the current network, and says so when it is not in it. */
function describeTarget(event: DisruptionEvent, catalogue: TargetCatalogue): TimelineTarget {
  const key = eventTargetKey(event);
  const base = {
    key,
    targetType: event.targetType,
    targetId: event.targetId,
    targetRegion: event.targetRegion,
  };

  if (event.targetType === DisruptionTargetType.REGION) {
    const region = catalogue.regions.find((entry) => entry.region === event.targetRegion);
    return {
      ...base,
      label: event.targetRegion ?? '(no region)',
      sublabel: region
        ? `region · ${region.nodeCount} node${region.nodeCount === 1 ? '' : 's'}`
        : 'region · not in this network',
      unresolved: !region,
    };
  }

  if (event.targetType === DisruptionTargetType.NODE) {
    const node = catalogue.nodes.find((candidate) => candidate.id === event.targetId);
    return {
      ...base,
      label: node?.name ?? `Node #${event.targetId}`,
      sublabel: node ? node.type.toLowerCase() : 'node · not in this network',
      unresolved: !node,
    };
  }

  const link = catalogue.links.find((candidate) => candidate.id === event.targetId);
  if (!link) {
    return { ...base, label: `Link #${event.targetId}`, sublabel: 'link · not in this network', unresolved: true };
  }
  const source = catalogue.nodes.find((node) => node.id === link.sourceNodeId);
  const target = catalogue.nodes.find((node) => node.id === link.targetNodeId);
  return {
    ...base,
    label: `${source?.name ?? link.sourceNodeId} → ${target?.name ?? link.targetNodeId}`,
    sublabel: 'link',
    unresolved: false,
  };
}

/** Nodes, then links, then regions; alphabetical within each. */
function compareTargets(a: TimelineTarget, b: TimelineTarget): number {
  const order: Record<DisruptionTargetType, number> = { NODE: 0, LINK: 1, REGION: 2 };
  return order[a.targetType] - order[b.targetType] || a.label.localeCompare(b.label);
}

function clampPercent(value: number, max = 100): number {
  if (!Number.isFinite(value)) {
    return 0;
  }
  return Math.min(Math.max(value, 0), Math.max(max, MIN_BAR_PERCENT));
}

/**
 * Tick positions for the chart's period axis, as percentages.
 *
 * At most `maxTicks`, on a round number of periods, so a 52-period horizon reads 0, 10, 20 … rather
 * than 0, 5.78, 11.56. The axis is in periods and not in dates: the model has a clock, not a
 * calendar.
 */
export function axisTicks(horizonPeriods: number, maxTicks = 11): readonly AxisTick[] {
  const span = Math.max(horizonPeriods, 1);
  const step = niceStep(span / Math.max(maxTicks - 1, 1));
  const ticks: AxisTick[] = [];
  for (let period = 0; period <= span; period += step) {
    ticks.push({ period, percent: (period / span) * 100 });
  }
  const last = ticks[ticks.length - 1];
  if (!last || last.period !== span) {
    ticks.push({ period: span, percent: 100 });
  }
  return ticks;
}

/** One gridline on the period axis. */
export interface AxisTick {
  readonly period: number;
  readonly percent: number;
}

/** The next 1, 2 or 5 times a power of ten at or above `raw` - the usual axis-step ladder. */
function niceStep(raw: number): number {
  if (!Number.isFinite(raw) || raw <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  for (const multiple of [1, 2, 5, 10]) {
    if (magnitude * multiple >= raw) {
      return Math.max(magnitude * multiple, 1);
    }
  }
  return Math.max(magnitude * 10, 1);
}
