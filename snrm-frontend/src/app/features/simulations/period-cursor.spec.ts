import { ElementSeriesIndex } from '../../core/element-series';
import { LinkTimeseries, NodeTimeseries, RunTimeseriesPoint } from '../../core/models';
import { networkSeries } from '../network-editor/network-series';
import { UNAVAILABLE_UNDERLAY_MAX } from '../network-editor/playback-channels';
import {
  NO_TINTS,
  clampPeriod,
  cursorTints,
  defaultCursorPeriod,
  fillScales,
  networkFiguresAt,
  steppedPeriod,
} from './period-cursor';

/**
 * The shared period cursor (FR-22).
 *
 * The figures are the four-echelon run of
 * `../snrm-backend/samples/four-echelon-playback/README.md` - §6.4 for the network
 * curve and §6.5/§8 for the elements - derived there by hand from the per-period loop rather than
 * produced by running the application. That is the same source `network-series.spec.ts`,
 * `playback-channels.spec.ts` and `element-charts.spec.ts` read, which is what makes a disagreement
 * between the canvas at period 11 and this page at period 11 a defect rather than a rounding
 * difference.
 *
 * Three things are pinned here and each of them is a *claim*, not a shape: where the cursor may
 * stand, where it opens, and what the page's two cursor-driven readings say at it.
 */

function point(
  period: number,
  totalDemand: number,
  servedDemand: number,
  cost: number,
  endingInventory: number,
  inPipeline: number,
  baselineServedDemand = servedDemand,
  baselineCost = cost,
): RunTimeseriesPoint {
  return {
    period,
    totalDemand,
    servedDemand,
    cost,
    baselineServedDemand,
    baselineCost,
    endingInventory,
    inPipeline,
  };
}

/** §6.4, periods 0–5 of the baseline run - the warm-up, where every column changes at least once. */
const BASELINE: readonly RunTimeseriesPoint[] = [
  point(0, 10, 10, 404, 40, 20),
  point(1, 10, 10, 405, 40, 20),
  point(2, 10, 10, 405, 40, 20),
  point(3, 10, 10, 402, 20, 30),
  point(4, 10, 10, 403, 20, 30),
  point(5, 10, 10, 403, 20, 30),
];

/** §8.2, periods 10–13 of the disruption run: the stockout, read as its own four-period run. */
const DISRUPTED: readonly RunTimeseriesPoint[] = [
  point(10, 10, 10, 404, 20, 20, 10, 403),
  point(11, 10, 0, 606, 50, 10, 10, 403),
  point(12, 10, 0, 608, 70, 0, 10, 403),
  point(13, 10, 0, 605, 50, 20, 10, 403),
];

function nodeSeries(overrides: Partial<NodeTimeseries> = {}): NodeTimeseries {
  return {
    nodeId: 3,
    name: 'DC-1',
    onHand: [5, 15, 25, 25, 5],
    inTransit: [0, 0, 0, 0, 0],
    arrivals: [10, 10, 0, 0, 10],
    served: [0, 0, 0, 0, 0],
    unserved: [0, 0, 0, 0, 0],
    throughput: [0, 0, 0, 0, 0],
    availability: [1, 1, 1, 1, 1],
    inboundLead: [1, 1, 1, 1, 1],
    baselineOnHand: [5, 5, 5, 5, 5],
    baselineServed: [0, 0, 0, 0, 0],
    ...overrides,
  };
}

function linkSeries(overrides: Partial<LinkTimeseries> = {}): LinkTimeseries {
  return {
    linkId: 30,
    sourceName: 'DC-1',
    targetName: 'CUST-1',
    flow: [10, 0, 0, 0, 20],
    utilisation: [0.1, null, null, null, 0.2],
    // §8.1: a STEP event holds availability at `1 − severity` for the whole window, here 1.0 severity
    // across periods 1–3 of this five-period slice.
    availability: [1, 0, 0, 0, 1],
    baselineFlow: [10, 10, 10, 10, 10],
    ...overrides,
  };
}

function index(
  nodes: readonly NodeTimeseries[] = [nodeSeries()],
  links: readonly LinkTimeseries[] = [linkSeries()],
): ElementSeriesIndex {
  return {
    nodes: new Map(nodes.map((series) => [series.nodeId, series])),
    links: new Map(links.map((series) => [series.linkId, series])),
  };
}

describe('period-cursor', () => {
  describe('clampPeriod', () => {
    it('keeps the cursor inside the run at both ends', () => {
      expect(clampPeriod(0, 30)).toBe(0);
      expect(clampPeriod(29, 30)).toBe(29);
      // 30 is the horizon, not a period of it: `RUN_TIMESERIES` is indexed 0…29.
      expect(clampPeriod(30, 30)).toBe(29);
      expect(clampPeriod(-4, 30)).toBe(0);
    });

    it('floors a fractional period onto the band it sits in', () => {
      // The rule `playback-clock.periodOf` states: a period is a half-open interval, so 3.7 is in
      // period 3. A round would show period 4 for the second half of period 3.
      expect(clampPeriod(3.7, 30)).toBe(3);
    });

    it('answers 0 for a run with no periods rather than −1', () => {
      // A horizon of 0 is a run that has not written its series. The transport is not rendered then,
      // but the clamp is total so no caller has to check first.
      expect(clampPeriod(5, 0)).toBe(0);
      expect(clampPeriod(0, 0)).toBe(0);
    });

    it('answers 0 for a period that is not a number', () => {
      expect(clampPeriod(Number.NaN, 30)).toBe(0);
      expect(clampPeriod(Number.POSITIVE_INFINITY, 30)).toBe(29);
    });
  });

  describe('defaultCursorPeriod', () => {
    it('opens on the run’s last period - the end state, which is what "the result" means', () => {
      expect(defaultCursorPeriod(30)).toBe(29);
      expect(defaultCursorPeriod(52)).toBe(51);
    });

    it('opens on 0 for a one-period run and for a run with no series', () => {
      expect(defaultCursorPeriod(1)).toBe(0);
      expect(defaultCursorPeriod(0)).toBe(0);
    });
  });

  describe('steppedPeriod', () => {
    it('steps one period either way', () => {
      expect(steppedPeriod(14, 1, 30)).toBe(15);
      expect(steppedPeriod(14, -1, 30)).toBe(13);
    });

    it('stops at the ends rather than wrapping', () => {
      // A cursor is a position, not a clock: there is nothing past the horizon to loop back from.
      expect(steppedPeriod(29, 1, 30)).toBe(29);
      expect(steppedPeriod(0, -1, 30)).toBe(0);
    });

    it('clamps a cursor that is already out of range before stepping it', () => {
      expect(steppedPeriod(400, -1, 30)).toBe(28);
    });
  });

  describe('networkFiguresAt', () => {
    it('reads the run’s own curve at the cursor (§6.4)', () => {
      const figures = networkFiguresAt(networkSeries(BASELINE), 3);

      expect(figures.period).toBe(3);
      expect(figures.served).toBe(10);
      expect(figures.demand).toBe(10);
      expect(figures.cost).toBe(402);
      expect(figures.onHand).toBe(20);
      expect(figures.inPipeline).toBe(30);
    });

    it('accumulates cost and unmet demand **to** the cursor, inclusive', () => {
      // 404 + 405 + 405 + 402 = 1616, the document's own subtotal for the warm-up. Inclusive because
      // the figure stands beside period 3's own cost: a total that excluded it would be the spend
      // before the period on screen.
      expect(networkFiguresAt(networkSeries(BASELINE), 3).costToCursor).toBe(1616);

      const stockout = networkSeries(DISRUPTED);
      expect(networkFiguresAt(stockout, 0).unmetToCursor).toBe(0);
      expect(networkFiguresAt(stockout, 1).unmetToCursor).toBe(10);
      expect(networkFiguresAt(stockout, 3).unmetToCursor).toBe(30);
    });

    it('reads the fill rate the curve draws, not a second division of its own', () => {
      // The cursor line and the figure beside it are the same period on one screen, so the
      // figure comes off `NetworkSeries.fill` - which the curve is drawn from - rather than being
      // recomputed as served/demand here.
      const figures = networkFiguresAt(networkSeries(DISRUPTED), 1);
      expect(figures.fill).toBe(0);
      expect(figures.noDemand).toBe(false);
    });

    it('states a quiet period rather than dashing the fill rate the engine reads as 1', () => {
      const quiet = networkSeries([point(0, 0, 0, 400, 20, 30)]);
      const figures = networkFiguresAt(quiet, 0);

      expect(figures.fill).toBe(1);
      expect(figures.noDemand).toBe(true);
      expect(figures.demand).toBe(0);
    });

    it('answers null past the end of the series rather than the last period’s numbers', () => {
      const figures = networkFiguresAt(networkSeries(BASELINE), 40);

      expect(figures.cost).toBeNull();
      expect(figures.onHand).toBeNull();
      expect(figures.costToCursor).toBeNull();
      expect(figures.noDemand).toBe(false);
    });
  });

  describe('fillScales', () => {
    it('takes each node’s own horizon maximum, once', () => {
      expect(fillScales(index()).get(3)).toBe(25);
    });

    it('leaves a node that never holds stock at 0 rather than dividing by it', () => {
      const scales = fillScales(index([nodeSeries({ onHand: [0, 0, 0] })]));
      expect(scales.get(3)).toBe(0);
    });

    it('answers an empty map for a run whose element series has not been read', () => {
      expect(fillScales(null).size).toBe(0);
    });
  });

  describe('cursorTints', () => {
    it('fills a node against its own maximum, never across the network', () => {
      const scales = fillScales(index());
      // 25 is `DC-1`'s own peak, so the blockage at period 2 is a full dot and the steady state at
      // period 0 is a fifth of one. Normalising across the network would flatten every node but the
      // largest - `playback-channels`' module note argues this at length.
      expect(cursorTints(index(), scales, 2).nodes.get(3)?.fill).toBe(1);
      expect(cursorTints(index(), scales, 0).nodes.get(3)?.fill).toBe(0.2);
    });

    it('turns lost availability into the canvas’s own underlay opacity (§8.1)', () => {
      const tints = cursorTints(index(), fillScales(index()), 2);

      // The arc is struck: `0.35 × (1 − 0)`, which is exactly the weight the scenario-authoring halo
      // carries on the canvas - one arithmetic, two surfaces.
      expect(tints.links.get(30)?.dim).toBe(UNAVAILABLE_UNDERLAY_MAX);
      // The node beside it is untouched, so it carries no halo at all rather than a faint one.
      expect(tints.nodes.get(3)?.dim).toBe(0);
    });

    it('recovers the moment availability does', () => {
      const scales = fillScales(index());
      expect(cursorTints(index(), scales, 3).links.get(30)?.dim).toBe(UNAVAILABLE_UNDERLAY_MAX);
      expect(cursorTints(index(), scales, 4).links.get(30)?.dim).toBe(0);
    });

    it('rounds to what the screen can draw, so an unchanged pixel writes no attribute', () => {
      const thirds = index([nodeSeries({ onHand: [1, 3, 3], availability: [1 / 3, 1, 1] })]);
      const tint = cursorTints(thirds, fillScales(thirds), 0).nodes.get(3);

      expect(tint?.fill).toBe(0.33);
      expect(tint?.dim).toBe(0.233);
    });

    it('gives an element the run has no series for no tint at all', () => {
      // Absent renders absent: the miniature leaves such a dot in its ordinary drawing,
      // exactly as `applyPlayback` leaves such a node in its ordinary styling on the canvas. A tint
      // of 0 would say the node was empty and fully available, which is a claim about a node the run
      // recorded nothing for.
      const tints = cursorTints(index(), fillScales(index()), 0);
      expect(tints.nodes.has(99)).toBe(false);
      expect(tints.links.has(99)).toBe(false);
    });

    it('answers the shared empty tints for a run whose series has not been read', () => {
      expect(cursorTints(null, new Map(), 3)).toBe(NO_TINTS);
    });

    it('clamps a cursor past the end of an element series rather than drawing nothing', () => {
      // A clamp, not a claim: `valueAt` is what the canvas uses, and a channel reading `undefined`
      // renders as no tint at all rather than as an error anybody would notice.
      const tints = cursorTints(index(), fillScales(index()), 99);
      expect(tints.nodes.get(3)?.fill).toBe(0.2);
    });
  });
});
