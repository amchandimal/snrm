import { ElementTimeseries, LinkTimeseries, NodeTimeseries } from '../../core/models';
import {
  FLOW_WIDTH_MAX,
  FLOW_WIDTH_MIN,
  UNAVAILABLE_UNDERLAY_MAX,
  fillLevel,
  flowWidth,
  gaugeColours,
  gaugeStops,
  indexElements,
  seriesMax,
  unavailableOpacity,
  valueAt,
} from './playback-channels';

/**
 * The numbers below are `samples/four-echelon-playback/README.md` §6.5, transcribed.
 *
 * That document derives the whole 30-period baseline run by hand from the per-period simulation
 * loop - nothing in it was produced by running the application - so a channel that disagrees with
 * it is a defect in one of the two rather than a rounding difference. `SUP-1`'s bulge at period 2
 * and `DC-1`'s drop at the same period are the two the manual test watches on screen.
 */
const SUP_ON_HAND = [10, 10, 20, 10, 10, 10];
const DC_ON_HAND = [15, 15, 5, 5, 5, 5];
const LINK_A_FLOW = [10, 10, 0, 10, 10, 10];
const LINK_C_FLOW = [0, 10, 10, 10, 10, 10];

function node(nodeId: number, name: string, onHand: readonly number[]): NodeTimeseries {
  const zeroes = onHand.map(() => 0);
  return {
    nodeId,
    name,
    onHand,
    inTransit: zeroes,
    arrivals: zeroes,
    served: zeroes,
    unserved: zeroes,
    throughput: zeroes,
    availability: onHand.map(() => 1),
    inboundLead: onHand.map(() => null),
    baselineOnHand: onHand,
    baselineServed: zeroes,
  };
}

function link(linkId: number, flow: readonly number[]): LinkTimeseries {
  return {
    linkId,
    sourceName: 'SUP-1',
    targetName: 'PLANT-1',
    flow,
    utilisation: flow.map((value) => value / 100),
    availability: flow.map(() => 1),
    baselineFlow: flow,
  };
}

describe('playback-channels', () => {
  describe('seriesMax', () => {
    it('takes the largest value over the whole horizon', () => {
      expect(seriesMax(SUP_ON_HAND)).toBe(20);
      expect(seriesMax(DC_ON_HAND)).toBe(15);
      expect(seriesMax(LINK_A_FLOW)).toBe(10);
    });

    it('answers 0 for an empty, absent or all-zero series', () => {
      expect(seriesMax([])).toBe(0);
      expect(seriesMax(undefined)).toBe(0);
      expect(seriesMax([0, 0, 0])).toBe(0);
    });

    it('never returns a negative maximum', () => {
      // A stock or a flow cannot be negative; inverting the scale would be a worse answer than
      // treating the element as one that never holds anything.
      expect(seriesMax([-4, -9])).toBe(0);
    });

    it('ignores non-finite entries rather than propagating them', () => {
      expect(seriesMax([5, Number.NaN, 12])).toBe(12);
    });
  });

  describe('valueAt', () => {
    it('reads index t as period t', () => {
      expect(valueAt(SUP_ON_HAND, 0)).toBe(10);
      expect(valueAt(SUP_ON_HAND, 2)).toBe(20);
    });

    it('clamps into the series rather than reading off the end', () => {
      expect(valueAt(SUP_ON_HAND, 99)).toBe(10);
      expect(valueAt(SUP_ON_HAND, -3)).toBe(10);
      expect(valueAt([], 0)).toBe(0);
      expect(valueAt(undefined, 0)).toBe(0);
    });
  });

  describe('fillLevel', () => {
    it('normalises each node against its own maximum (§6.5.1)', () => {
      // SUP-1 peaks at 20 in period 2 - its own order-up-to target - and sits at 10 otherwise.
      expect(fillLevel(SUP_ON_HAND[2], 20)).toBe(1);
      expect(fillLevel(SUP_ON_HAND[0], 20)).toBe(0.5);
      expect(fillLevel(SUP_ON_HAND[4], 20)).toBe(0.5);

      // DC-1 holds 15 through the warm-up and drops to 5 at period 2, where it stays.
      expect(fillLevel(DC_ON_HAND[0], 15)).toBe(1);
      expect(fillLevel(DC_ON_HAND[2], 15)).toBeCloseTo(1 / 3, 10);
    });

    it('reads 0 for a node that never holds stock', () => {
      // CUST-1 ends every period from 1 onward at 0 - a pass-through, not a buffer. Its own maximum
      // is what makes the gauge readable at all; a network-wide one would flatten it either way.
      expect(fillLevel(0, 0)).toBe(0);
      expect(fillLevel(4, 0)).toBe(0);
    });

    it('clamps into [0,1] and tolerates a non-finite reading', () => {
      expect(fillLevel(30, 20)).toBe(1);
      expect(fillLevel(-5, 20)).toBe(0);
      expect(fillLevel(Number.NaN, 20)).toBe(0);
      expect(fillLevel(10, Number.NaN)).toBe(0);
    });
  });

  describe('flowWidth', () => {
    it('maps an arc linearly against its own busiest period (§6.5.2)', () => {
      expect(flowWidth(LINK_A_FLOW[0], 10)).toBe(FLOW_WIDTH_MAX);
      expect(flowWidth(5, 10)).toBe((FLOW_WIDTH_MIN + FLOW_WIDTH_MAX) / 2);
    });

    it('draws an idle arc at the minimum width rather than at none', () => {
      // Arc a carries nothing in period 2 and arc c carries nothing in period 0 - the two zeros the
      // manual test watches. The arc stays on the canvas: a link that vanished would read as a
      // deleted one, and a stall is exactly when the structure has to stay legible.
      expect(flowWidth(LINK_A_FLOW[2], 10)).toBe(FLOW_WIDTH_MIN);
      expect(flowWidth(LINK_C_FLOW[0], 10)).toBe(FLOW_WIDTH_MIN);
    });

    it('falls back to the minimum for an arc that never carries anything', () => {
      expect(flowWidth(0, 0)).toBe(FLOW_WIDTH_MIN);
      expect(flowWidth(Number.NaN, 10)).toBe(FLOW_WIDTH_MIN);
    });

    it('never exceeds the maximum width', () => {
      expect(flowWidth(40, 10)).toBe(FLOW_WIDTH_MAX);
    });
  });

  describe('unavailableOpacity', () => {
    it('is nothing at full availability and the full halo at none', () => {
      expect(unavailableOpacity(1)).toBe(0);
      expect(unavailableOpacity(0)).toBe(UNAVAILABLE_UNDERLAY_MAX);
    });

    it('is linear in the outage between them', () => {
      expect(unavailableOpacity(0.2)).toBeCloseTo(0.28, 10);
      expect(unavailableOpacity(0.5)).toBeCloseTo(0.175, 10);
    });

    it('clamps a reading outside [0,1]', () => {
      expect(unavailableOpacity(1.4)).toBe(0);
      expect(unavailableOpacity(-2)).toBe(UNAVAILABLE_UNDERLAY_MAX);
      expect(unavailableOpacity(Number.NaN)).toBe(0);
    });
  });

  describe('gaugeStops', () => {
    it('puts a hard line at the fill level, not a fade across the node', () => {
      expect(gaugeStops(0)).toBe('0% 0% 0% 100%');
      expect(gaugeStops(0.5)).toBe('0% 50% 50% 100%');
      expect(gaugeStops(1)).toBe('0% 100% 100% 100%');
    });

    it('rounds to whole percent, which is the resolution of a node', () => {
      expect(gaugeStops(1 / 3)).toBe('0% 33% 33% 100%');
      expect(gaugeStops(0.336)).toBe('0% 34% 34% 100%');
    });

    it('clamps a value outside [0,1]', () => {
      expect(gaugeStops(4)).toBe('0% 100% 100% 100%');
      expect(gaugeStops(-1)).toBe('0% 0% 0% 100%');
      expect(gaugeStops(Number.NaN)).toBe('0% 0% 0% 100%');
    });
  });

  describe('gaugeColours', () => {
    it('keeps the hue the node type and darkens only the filled part', () => {
      // The DC palette entry of echelon-rules.ts. Accent below the line, type colour above it.
      expect(gaugeColours('#2f8f83', '#1c5c54')).toBe('#1c5c54 #1c5c54 #2f8f83 #2f8f83');
    });
  });

  describe('indexElements', () => {
    const series: ElementTimeseries = {
      available: true,
      nodes: [node(1, 'SUP-1', SUP_ON_HAND), node(3, 'DC-1', DC_ON_HAND)],
      links: [link(10, LINK_A_FLOW), link(12, LINK_C_FLOW)],
    };

    it('keys both maps by element id and takes each maximum once', () => {
      const indexed = indexElements(series);

      expect([...indexed.nodes.keys()]).toEqual([1, 3]);
      expect(indexed.nodes.get(1)?.maxOnHand).toBe(20);
      expect(indexed.nodes.get(3)?.maxOnHand).toBe(15);
      expect(indexed.links.get(10)?.maxFlow).toBe(10);
      expect(indexed.links.get(12)?.maxFlow).toBe(10);
    });

    it('carries the series through untouched, so the canvas reads the run and not a copy', () => {
      const indexed = indexElements(series);

      expect(indexed.nodes.get(1)?.series).toBe(series.nodes[0]);
      expect(indexed.links.get(10)?.series).toBe(series.links[0]);
    });

    it('indexes an available-but-empty answer as two empty maps', () => {
      // A run that recorded, on a network with nothing to record. Not the same as `available: false`,
      // which the store branches on before it gets here.
      const indexed = indexElements({ available: true, nodes: [], links: [] });

      expect(indexed.nodes.size).toBe(0);
      expect(indexed.links.size).toBe(0);
    });
  });
});
