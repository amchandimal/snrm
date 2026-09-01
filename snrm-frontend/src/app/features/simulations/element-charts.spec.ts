import { LinkTimeseries, NetworkLink, NodeTimeseries } from '../../core/models';
import { SparkBox, sparkline } from '../network-editor/sparkline-geometry';
import {
  UNCAPPED,
  inboundFlowFor,
  linkCharts,
  nodeCharts,
  periodTicksFor,
  readingAt,
  valueTicks,
} from './element-charts';

/**
 * What an element scope draws (FR-22).
 *
 * The figures are the four-echelon baseline run of
 * `../snrm-backend/samples/four-echelon-playback/README.md` §6.5, derived there by
 * hand from the per-period loop rather than produced by running the application - the same source
 * `sparkline-geometry.spec.ts` and `playback-channels.spec.ts` use, so a disagreement between the
 * editor's inspector and this page's charts is a defect in one of them rather than a rounding
 * difference.
 *
 * What is pinned here is not a shape - `sparkline-geometry.spec.ts` owns that - but the **readings**:
 * which array a chart draws, which of them has an undisrupted twin in the schema, what a gap in it
 * means, and which mean is the honest one to print.
 */

/** `DC-1`: 15 through the first two periods, then 5 for the rest (§6.5.1). */
const DC_ON_HAND = [15, 15, ...Array<number>(28).fill(5)];

/** Arc **c** `DC-1 → CUST-1`: idle at t=0 because `CUST-1` opened at its target (§6.5.2). */
const LINK_C_FLOW = [0, ...Array<number>(29).fill(10)];

/** `CUST-1`'s inbound lead: null exactly where arc **c** dispatched nothing (§6.5.4). */
const CUST_INBOUND_LEAD = [null, ...Array<number | null>(29).fill(1)];

const ZEROES = Array<number>(30).fill(0);

function nodeSeries(overrides: Partial<NodeTimeseries> = {}): NodeTimeseries {
  return {
    nodeId: 3,
    name: 'DC-1',
    onHand: DC_ON_HAND,
    inTransit: LINK_C_FLOW,
    arrivals: LINK_C_FLOW,
    served: ZEROES,
    unserved: ZEROES,
    throughput: ZEROES,
    availability: Array<number>(30).fill(1),
    inboundLead: CUST_INBOUND_LEAD,
    // §6.5.5: a baseline run copies its own series into the baseline columns, so these coincide.
    baselineOnHand: DC_ON_HAND,
    baselineServed: ZEROES,
    ...overrides,
  };
}

function linkSeries(overrides: Partial<LinkTimeseries> = {}): LinkTimeseries {
  return {
    linkId: 30,
    sourceName: 'DC-1',
    targetName: 'CUST-1',
    flow: LINK_C_FLOW,
    utilisation: LINK_C_FLOW.map((flow) => flow / 100),
    availability: Array<number>(30).fill(1),
    baselineFlow: LINK_C_FLOW,
    ...overrides,
  };
}

/** As much of a link as the weights need: which arc it is, and where it lands. */
function arc(id: number, targetNodeId: number): Pick<NetworkLink, 'id' | 'targetNodeId'> {
  return { id, targetNodeId };
}

describe('element-charts', () => {
  describe('nodeCharts', () => {
    it('draws the seven node quantities, with served and unserved as a pair', () => {
      const charts = nodeCharts(nodeSeries(), []);

      expect(charts.map((chart) => chart.key)).toEqual([
        'onHand',
        'inTransit',
        'arrivals',
        'served',
        'unserved',
        'throughput',
        'availability',
        'inboundLead',
      ]);
    });

    it('overlays a baseline only where the schema records one', () => {
      // `V9__element_timeseries.sql` records `baseline_on_hand`, `baseline_served` and
      // `baseline_flow` and nothing else. Drawing the others against a copy of themselves would
      // claim the disruption moved no material - the rule `network-series.ts` states one scope up.
      const charts = nodeCharts(nodeSeries(), []);
      const paired = charts.filter((chart) => chart.baseline !== null).map((chart) => chart.key);

      expect(paired).toEqual(['onHand', 'served']);
      expect(charts[0].baseline).toBe(DC_ON_HAND);
    });

    it('prints the same on-hand average the element inspector prints', () => {
      // §6.5.1: 170 over 30 periods. The 2 dp figure is what MANUAL-TEST reads off both screens.
      const onHand = nodeCharts(nodeSeries(), [])[0];

      expect(Number(onHand.mean!.toFixed(2))).toBe(5.67);
      expect(onHand.meanLabel).toBe('mean');
    });

    it('weights the inbound lead by what each period dispatched', () => {
      // A plain mean of [1, 5] is 3 and says a period that moved one unit as loudly as one that
      // moved ninety; the dispatch-weighted mean is 1.4. `inboundLead` is already dispatch-weighted
      // *within* a period, and the editor's inspector prints the weighted figure - one
      // node must not be given two different average lead times on two screens.
      const charts = nodeCharts(nodeSeries({ inboundLead: [1, 5] }), [90, 10]);
      const lead = charts.find((chart) => chart.key === 'inboundLead')!;

      expect(lead.mean).toBeCloseTo(1.4, 10);
      expect(lead.meanLabel).toBe('dispatch-weighted mean');
      expect(lead.unit).toBe('periods');
    });

    it('reports a node nothing was ever dispatched to as absent rather than zero', () => {
      // §6.5.4's structural case: a supply origin has no inbound arc at all, so its inbound lead is
      // undefined in every period of every run this network can produce.
      const charts = nodeCharts(
        nodeSeries({ inboundLead: Array<number | null>(30).fill(null) }),
        [],
      );
      const lead = charts.find((chart) => chart.key === 'inboundLead')!;

      expect(lead.mean).toBeNull();
      // And the line breaks rather than dipping: the sentence explains the gaps it leaves.
      expect(lead.absence).toContain('nothing was dispatched');
      expect(lead.absence).toContain('arrived instantly');
    });

    it('says nothing about gaps on a series that cannot have one', () => {
      const charts = nodeCharts(nodeSeries(), []);

      expect(charts.filter((chart) => chart.absence !== null).map((chart) => chart.key)).toEqual([
        'inboundLead',
      ]);
      expect(charts.every((chart) => chart.suppressed === null)).toBeTrue();
    });

    it('gives the at-cursor figure the inspector’s own three words for an absent lead (FR-22)', () => {
      // The short form of the footnote, for the figure beside the chart title where a sentence does
      // not fit. Verbatim from `element-inspector`, because a researcher reads period 3 of `CUST-1`
      // on both screens and two phrasings of one absence read as two different findings.
      const lead = nodeCharts(nodeSeries(), []).find((chart) => chart.key === 'inboundLead')!;

      expect(lead.absentReading).toBe('no inbound this period');
      // And never on a series whose nulls are impossible - those figures read `-`.
      const onHand = nodeCharts(nodeSeries(), [])[0];
      expect(onHand.absentReading).toBeNull();
    });
  });

  describe('linkCharts', () => {
    it('draws flow, utilisation and availability, and pairs flow alone', () => {
      const charts = linkCharts(linkSeries(), true);

      expect(charts.map((chart) => chart.key)).toEqual(['flow', 'utilisation', 'availability']);
      expect(charts[0].baseline).toBe(LINK_C_FLOW);
      expect(charts[1].baseline).toBeNull();
      expect(charts[2].baseline).toBeNull();
    });

    it('reads a gap in utilisation as no capacity available, on a capped arc', () => {
      // Null in this column means two different things and neither is a zero. On a capped
      // arc it is an outage that took the ceiling to zero - dark, not idle.
      const utilisation = linkCharts(linkSeries(), true)[1];

      expect(utilisation.suppressed).toBeNull();
      expect(utilisation.absence).toContain('no capacity was available');
      expect(utilisation.absentReading).toBe('no capacity available');
      expect(utilisation.unit).toBe('ratio');
    });

    it('replaces the chart with a sentence on an uncapped arc', () => {
      // Every period is null, so there is no line to break - and *uncapped* says more than an empty
      // chart, which is exactly the reading `element-inspector` already makes.
      const utilisation = linkCharts(
        linkSeries({ utilisation: Array<number | null>(30).fill(null) }),
        false,
      )[1];

      expect(utilisation.suppressed).toBe(UNCAPPED);
      expect(utilisation.absence).toBeNull();
      expect(utilisation.mean).toBeNull();
    });

    it('draws a 0 that is a real zero', () => {
      // §6.5.2: `utilisation` is exactly 0.0 - not null - wherever the flow is 0 at full
      // availability. An idle arc at full availability is idle, not unmeasurable.
      const utilisation = linkCharts(linkSeries(), true)[1];

      expect(utilisation.values[0]).toBe(0);
      expect(sparkline(utilisation.values, { width: 110, height: 22, pad: 5 }).paths.length).toBe(1);
    });
  });

  describe('inboundFlowFor', () => {
    it('sums the arcs that end at the node, index for index', () => {
      // §6.5.4: `CUST-1`'s only inbound arc is **c**, and its lead is null in exactly the period
      // that arc carried nothing. The two align with no shift - a link's flow in period t is what
      // was dispatched then, and the node's lead for t is the lead of that dispatch.
      const series = new Map([[30, linkSeries()]]);
      const weights = inboundFlowFor(4, [arc(30, 4)], series);

      expect(weights).toEqual(LINK_C_FLOW);
    });

    it('answers empty for a node with no inbound arc', () => {
      const series = new Map([[30, linkSeries()]]);

      expect(inboundFlowFor(1, [arc(30, 4)], series)).toEqual([]);
    });

    it('adds several inbound arcs together', () => {
      const series = new Map([
        [30, linkSeries({ flow: [1, 2, 3] })],
        [31, linkSeries({ linkId: 31, flow: [10, 20, 30] })],
      ]);

      expect(inboundFlowFor(4, [arc(30, 4), arc(31, 4)], series)).toEqual([11, 22, 33]);
    });
  });

  describe('the axes', () => {
    const BOX: SparkBox = { width: 110, height: 22, pad: 5 };

    it('labels the fitted range at both ends and through the middle', () => {
      // Three, not a round-number ladder: the scale is this element's own range, so the two numbers
      // a reader needs from it are the ends. `valueY` places them, so a label cannot sit somewhere
      // the line does not.
      const frame = sparkline([10, 20, null, 5, 15], BOX);

      expect(valueTicks(frame)).toEqual([
        { value: 20, y: 5 },
        { value: 12.5, y: 11 },
        { value: 5, y: 17 },
      ]);
    });

    it('gives a flat series one tick, at the line', () => {
      // Its range is degenerate; a top and a bottom label reading the same number would claim a
      // scale the series does not have. `valueY` already draws it through the middle.
      const frame = sparkline([4, 4, 4], BOX);

      expect(valueTicks(frame)).toEqual([{ value: 4, y: 11 }]);
    });

    it('puts the period labels at the centre of their bands', () => {
      // A step chart draws a period as a band, so the label sits under the middle of the band it
      // names - `cursorX`'s own rule, which is what will put FR-22's cursor through its own tick.
      const frame = sparkline([10, 20, 30, 40, 50], BOX);

      expect(periodTicksFor(frame)).toEqual([
        { period: 0, x: 15 },
        { period: 1, x: 35 },
        { period: 2, x: 55 },
        { period: 3, x: 75 },
        { period: 4, x: 95 },
      ]);
    });

    it('thins the period labels on a long horizon, as the performance curve does', () => {
      // `periodTicks` from `curve-geometry.ts` chooses which periods get one, so the two scopes tick
      // the same horizon at the same places and a reader moving between them re-learns nothing.
      const frame = sparkline(Array<number>(30).fill(1), BOX);

      expect(periodTicksFor(frame).map((tick) => tick.period)).toEqual([0, 4, 8, 12, 16, 20, 24, 28, 29]);
    });
  });

  describe('readingAt', () => {
    it('answers null for an absent period rather than zero', () => {
      // The at-period figure of FR-22's cursor: it feeds a sentence, where a 0 would be a claim.
      expect(readingAt(CUST_INBOUND_LEAD, 0)).toBeNull();
      expect(readingAt(CUST_INBOUND_LEAD, 1)).toBe(1);
    });

    it('clamps into the series at both ends', () => {
      expect(readingAt([5, 6, 7], -2)).toBe(5);
      expect(readingAt([5, 6, 7], 99)).toBe(7);
      expect(readingAt([], 0)).toBeNull();
      expect(readingAt(undefined, 0)).toBeNull();
    });
  });
});
