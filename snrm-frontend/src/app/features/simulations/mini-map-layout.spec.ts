import { NetworkLink, NetworkNode, NodeType } from '../../core/models';
import { ECHELON_RANK, nodeTypeProfile } from '../network-editor/echelon-rules';
import { MiniMapBox, echelonPositions, hasStoredCoordinates, miniMap } from './mini-map-layout';

/**
 * The network inspector's layout (FR-22).
 *
 * ```text
 *   inner left/top   5 + 5 = 10        pad + nodeRadius, so a dot at the edge is not clipped
 *   inner width      100 − 2×10 = 80
 *   inner height      60 − 2×10 = 40
 * ```
 *
 * The box is chosen so every expectation below is exact: a 200 × 100 network scales by 0.4 and fills
 * it, and a 200 × 50 one scales by 0.4 as well and is centred vertically - which is the whole of the
 * aspect-ratio rule, in two numbers.
 */
const BOX: MiniMapBox = { width: 100, height: 60, pad: 5, nodeRadius: 5 };

let nextId = 1;

function node(
  name: string,
  type: NodeType,
  posX: number | null,
  posY: number | null,
): NetworkNode {
  return {
    id: nextId++,
    networkId: 1,
    name,
    type,
    capacity: { value: null, timeUnit: 'DAY' },
    processingTime: { value: 0, unit: 'DAY' },
    fixedCost: 0,
    varCost: 0,
    failureProb: 0,
    region: null,
    lat: null,
    lng: null,
    posX,
    posY,
    // FR-30 is an editor feature; the dashboard's miniature draws no captions.
    caption: null,
    captionVisible: true,
    createdAt: '2026-08-07T00:00:00Z',
    updatedAt: '2026-08-07T00:00:00Z',
  };
}

function link(id: number, sourceNodeId: number, targetNodeId: number): NetworkLink {
  return {
    id,
    networkId: 1,
    sourceNodeId,
    targetNodeId,
    leadTime: { value: 1, unit: 'DAY' },
    capacity: { value: null, timeUnit: 'DAY' },
    unitCost: 0,
    failureProb: 0,
    caption: null,
    captionVisible: true,
    createdAt: '2026-08-07T00:00:00Z',
    updatedAt: '2026-08-07T00:00:00Z',
  };
}

describe('mini-map-layout', () => {
  beforeEach(() => {
    nextId = 1;
  });

  describe('the stored arrangement', () => {
    it('fits the canvas coordinates to the panel', () => {
      const a = node('A', 'SUPPLIER', 0, 0);
      const b = node('B', 'PLANT', 200, 0);
      const c = node('C', 'DC', 200, 100);

      const map = miniMap([a, b, c], [], BOX);

      // 200 × 100 against 80 × 40: both axes scale by 0.4, so the fit uses the whole box.
      expect(map.arrangement).toBe('stored');
      expect(map.nodes.map((entry) => [entry.x, entry.y])).toEqual([
        [10, 10],
        [90, 10],
        [90, 50],
      ]);
    });

    it('preserves the aspect ratio and centres what the scale did not fill', () => {
      // A 200 × 50 network. Fitting each axis independently would scale y by 0.8 and redraw a wide
      // flat chain as a square - and the miniature would stop being recognisable as the canvas it
      // copies, which is the only thing it is for.
      const a = node('A', 'SUPPLIER', 0, 0);
      const b = node('B', 'PLANT', 200, 0);
      const c = node('C', 'DC', 200, 50);

      const map = miniMap([a, b, c], [], BOX);

      expect(map.nodes.map((entry) => [entry.x, entry.y])).toEqual([
        [10, 20],
        [90, 20],
        [90, 40],
      ]);
    });

    it('centres a single node instead of dividing by a zero span', () => {
      const map = miniMap([node('A', 'DC', 640, 480)], [], BOX);

      expect(map.nodes[0].x).toBe(50);
      expect(map.nodes[0].y).toBe(30);
    });

    it('fits a network whose nodes are all on one vertical line', () => {
      const a = node('A', 'SUPPLIER', 7, 0);
      const b = node('B', 'PLANT', 7, 100);

      const map = miniMap([a, b], [], BOX);

      expect(map.nodes.map((entry) => [entry.x, entry.y])).toEqual([
        [50, 10],
        [50, 50],
      ]);
    });

    it('needs both coordinates on every node', () => {
      expect(hasStoredCoordinates([node('A', 'DC', 1, 2)])).toBeTrue();
      // Half a coordinate is not half a layout: it is a row an importer wrote without one.
      expect(hasStoredCoordinates([node('A', 'DC', 1, null)])).toBeFalse();
      expect(hasStoredCoordinates([node('A', 'DC', 1, 2), node('B', 'DC', null, null)])).toBeFalse();
    });
  });

  describe('the echelon fallback', () => {
    it('lays the whole graph out by echelon when any node has no coordinates', () => {
      // The decision is taken for the network, not per node: a synthesised position and a stored one
      // are not in the same space, and a picture mixing them is a picture of neither.
      const sup = node('SUP-1', 'SUPPLIER', null, null);
      const plant = node('PLANT-1', 'PLANT', 400, 120);
      const dc1 = node('DC-1', 'DC', 800, 60);
      const dc2 = node('DC-2', 'DC', 800, 180);
      const cust = node('CUST-1', 'CUSTOMER', 1200, 120);

      const map = miniMap([sup, plant, dc1, dc2, cust], [], BOX);

      expect(map.arrangement).toBe('echelon');
      // Four columns in the canonical flow order, one per echelon: 3 columns of span across 80 units
      // of width scales by 80/3, and the two DCs share the third column.
      expect(map.nodes.map((entry) => entry.x)).toEqual([10, 36.67, 63.33, 63.33, 90]);
      expect(map.nodes.map((entry) => entry.y)).toEqual([30, 30, 16.67, 43.33, 30]);
    });

    it('orders a column by name, so the same network draws the same way twice', () => {
      // Insertion order would make the picture depend on the order the API happened to answer in.
      const zulu = node('Zulu', 'DC', null, null);
      const alpha = node('Alpha', 'DC', null, null);

      const positions = echelonPositions([zulu, alpha]);

      expect(positions.get(alpha.id)!.y).toBeLessThan(positions.get(zulu.id)!.y);
      expect(positions.get(alpha.id)!.x).toBe(ECHELON_RANK.DC);
    });

    it('centres each column on its middle row', () => {
      // One supplier against three customers draws as a fan rather than as a staircase.
      const sup = node('SUP-1', 'SUPPLIER', null, null);
      const a = node('A', 'CUSTOMER', null, null);
      const b = node('B', 'CUSTOMER', null, null);
      const c = node('C', 'CUSTOMER', null, null);

      const positions = echelonPositions([sup, a, b, c]);

      expect(positions.get(sup.id)).toEqual({ x: 0, y: 0 });
      expect(positions.get(a.id)!.y).toBe(-1);
      expect(positions.get(b.id)!.y).toBe(0);
      expect(positions.get(c.id)!.y).toBe(1);
    });
  });

  describe('colour', () => {
    it('takes both colours from the editor’s palette rather than restating them', () => {
      // Asserted against `nodeTypeProfile` itself, as `disruption-overlay.spec.ts` asserts against
      // `placeBar`: the point of the cross-feature import is that there is one palette, and a spec
      // that transcribed the hex codes would pass while the two drifted apart.
      const map = miniMap(
        [node('A', 'PLANT', 0, 0), node('B', 'CUSTOMER', 100, 0)],
        [],
        BOX,
      );

      expect(map.nodes[0].colour).toBe(nodeTypeProfile('PLANT').colour);
      expect(map.nodes[0].accent).toBe(nodeTypeProfile('PLANT').accent);
      expect(map.nodes[1].colour).toBe(nodeTypeProfile('CUSTOMER').colour);
    });
  });

  describe('arcs', () => {
    it('trims both ends off the node dots and points the head at the target', () => {
      const a = node('A', 'SUPPLIER', 0, 0);
      const b = node('B', 'PLANT', 200, 0);

      const map = miniMap([a, b], [link(90, a.id, b.id)], BOX);

      // Nodes at (10, 30) and (90, 30); trimmed by nodeRadius + 1.5 at each end.
      expect(map.links[0]).toEqual({
        id: 90,
        sourceNodeId: a.id,
        targetNodeId: b.id,
        x1: 16.5,
        y1: 30,
        x2: 83.5,
        y2: 30,
        // Tip at the trimmed end, base one arrow-length back, 3.5 either side of the arc: the
        // direction is drawn rather than implied, because a network's arcs are directed.
        arrow: '83.5,30 76.5,33.5 76.5,26.5',
        label: 'A → B',
      });
    });

    it('claims no direction between two nodes on the same point', () => {
      // Nothing stops a researcher dropping one node on another, and a unit vector of a zero-length
      // arc is a division by zero pointed at the DOM.
      const a = node('A', 'SUPPLIER', 40, 40);
      const b = node('B', 'PLANT', 40, 40);

      const map = miniMap([a, b], [link(1, a.id, b.id)], BOX);

      expect(map.links[0].arrow).toBeNull();
      expect(map.links[0].x1).toBe(map.links[0].x2);
    });

    it('drops an arc whose endpoints are not both on the map', () => {
      // The nodes and the links arrive in two responses; a half-loaded pair must not throw inside a
      // view update or draw a line to nowhere.
      const a = node('A', 'SUPPLIER', 0, 0);
      const b = node('B', 'PLANT', 200, 0);

      const map = miniMap([a, b], [link(1, a.id, b.id), link(2, a.id, 999)], BOX);

      expect(map.links.map((arc) => arc.id)).toEqual([1]);
    });
  });

  it('is empty for a network with no nodes, and says so rather than drawing one', () => {
    const map = miniMap([], [link(1, 1, 2)], BOX);

    expect(map).toEqual({ nodes: [], links: [], arrangement: 'empty', empty: true });
  });
});
