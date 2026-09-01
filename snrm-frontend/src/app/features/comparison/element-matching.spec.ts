import { NetworkLink, NetworkNode, NodeType } from '../../core/models';
import {
  absenceSentence,
  keyOfLink,
  keyOfNode,
  linkKey,
  linkLabel,
  matchLink,
  matchNode,
  nodeKey,
  nodeNames,
  sameKey,
  tallyMatches,
} from './element-matching';

/**
 * The by-name element selection the panes share (FR-25).
 *
 * The fixtures are two variants of one network, which is the case the view exists for: the same
 * names on both sides but for the one element that was added, removed, re-cased or reversed. Every
 * expectation below is one of those five readings.
 */

let nextId = 100;

function node(name: string, type: NodeType = 'DC'): NetworkNode {
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
    posX: null,
    posY: null,
    // FR-30 is an editor feature; the side-by-side panes match on names and draw no captions.
    caption: null,
    captionVisible: true,
    createdAt: '2026-08-08T00:00:00Z',
    updatedAt: '2026-08-08T00:00:00Z',
  };
}

function link(source: NetworkNode, target: NetworkNode): NetworkLink {
  return {
    id: nextId++,
    networkId: 1,
    sourceNodeId: source.id,
    targetNodeId: target.id,
    leadTime: { value: 1, unit: 'DAY' },
    capacity: { value: null, timeUnit: 'DAY' },
    unitCost: 0,
    failureProb: 0,
    caption: null,
    captionVisible: true,
    createdAt: '2026-08-08T00:00:00Z',
    updatedAt: '2026-08-08T00:00:00Z',
  };
}

describe('element-matching', () => {
  beforeEach(() => {
    nextId = 100;
  });

  describe('a node, matched by name', () => {
    it('finds the same-named node, which is a different row in every variant', () => {
      const here = node('DC-1');
      const clicked = node('DC-1');

      // Two networks, two ids, one name - the whole reason the match is by name.
      expect(here.id).not.toBe(clicked.id);
      expect(matchNode(keyOfNode(clicked), [node('SUP-1', 'SUPPLIER'), here]).element).toBe(here);
    });

    it('says there is none where the variant does not carry the name', () => {
      const match = matchNode(nodeKey('DC-2'), [node('DC-1'), node('DC-3')]);
      expect(match.element).toBeNull();
      expect(match.nearMiss).toBeNull();
    });

    it('does not match on case, and reports the near miss instead of folding it in', () => {
      const lower = node('dc-1');
      const match = matchNode(nodeKey('DC-1'), [lower]);

      expect(match.element).toBeNull();
      expect(match.nearMiss).toBe(lower);
    });

    it('prefers the exact node even when a differently-cased one comes first', () => {
      const lower = node('dc-1');
      const exact = node('DC-1');
      const match = matchNode(nodeKey('DC-1'), [lower, exact]);

      expect(match.element).toBe(exact);
      expect(match.nearMiss).toBeNull();
    });

    it('answers a link key with nothing rather than searching the nodes for it', () => {
      expect(matchNode(linkKey('A', 'B'), [node('A')]).element).toBeNull();
    });
  });

  describe('a link, matched on its two endpoint names', () => {
    it('finds the arc between the same two names', () => {
      const plant = node('PLANT-1', 'PLANT');
      const dc = node('DC-1');
      const arc = link(plant, dc);

      const match = matchLink(linkKey('PLANT-1', 'DC-1'), [arc], nodeNames([plant, dc]));
      expect(match.element).toBe(arc);
    });

    it('treats the reverse arc as a different link, and names it', () => {
      const plant = node('PLANT-1', 'PLANT');
      const dc = node('DC-1');
      const back = link(dc, plant);

      const match = matchLink(linkKey('PLANT-1', 'DC-1'), [back], nodeNames([plant, dc]));
      expect(match.element).toBeNull();
      expect(match.reversed).toBe(back);
    });

    it('ignores a reverse arc once the arc asked for is there', () => {
      const plant = node('PLANT-1', 'PLANT');
      const dc = node('DC-1');
      const back = link(dc, plant);
      const forward = link(plant, dc);

      const match = matchLink(
        linkKey('PLANT-1', 'DC-1'),
        [back, forward],
        nodeNames([plant, dc]),
      );
      expect(match.element).toBe(forward);
      expect(match.reversed).toBeNull();
    });

    it('reports a case-only difference in an endpoint as a near miss', () => {
      const plant = node('plant-1', 'PLANT');
      const dc = node('DC-1');
      const arc = link(plant, dc);

      const match = matchLink(linkKey('PLANT-1', 'DC-1'), [arc], nodeNames([plant, dc]));
      expect(match.element).toBeNull();
      expect(match.nearMiss).toBe(arc);
    });

    it('skips a link whose endpoints this pane cannot name', () => {
      const plant = node('PLANT-1', 'PLANT');
      const dc = node('DC-1');
      const arc = link(plant, dc);

      // The nodes response has not landed yet: no names, so no claim either way.
      const match = matchLink(linkKey('PLANT-1', 'DC-1'), [arc], new Map());
      expect(match.element).toBeNull();
      expect(match.reversed).toBeNull();
    });
  });

  describe('keyOfLink', () => {
    it('composes the key from the endpoint names', () => {
      const plant = node('PLANT-1', 'PLANT');
      const dc = node('DC-1');
      expect(keyOfLink(link(plant, dc), nodeNames([plant, dc]))).toEqual(
        linkKey('PLANT-1', 'DC-1'),
      );
    });

    it('answers null rather than a key naming an id nothing else could match', () => {
      const plant = node('PLANT-1', 'PLANT');
      const dc = node('DC-1');
      expect(keyOfLink(link(plant, dc), nodeNames([plant]))).toBeNull();
    });
  });

  describe('sameKey', () => {
    it('is true for one element named twice and false across kinds', () => {
      expect(sameKey(nodeKey('DC-1'), nodeKey('DC-1'))).toBe(true);
      expect(sameKey(nodeKey('DC-1'), nodeKey('dc-1'))).toBe(false);
      expect(sameKey(linkKey('A', 'B'), linkKey('A', 'B'))).toBe(true);
      expect(sameKey(linkKey('A', 'B'), linkKey('B', 'A'))).toBe(false);
      expect(sameKey(nodeKey('A'), linkKey('A', 'B'))).toBe(false);
      expect(sameKey(null, null)).toBe(true);
      expect(sameKey(null, nodeKey('A'))).toBe(false);
    });
  });

  describe('absenceSentence', () => {
    it('names the configuration, not the pane', () => {
      const sentence = absenceSentence(
        nodeKey('DC-1'),
        'Baseline v3',
        { element: null, nearMiss: null, reversed: null },
        (n: NetworkNode) => n.name,
      );
      expect(sentence).toBe('DC-1 is not in Baseline v3.');
    });

    it('adds both qualifications when both apply', () => {
      const plant = node('PLANT-1', 'PLANT');
      const dc = node('dc-1');
      const names = nodeNames([plant, dc]);
      const nearMiss = link(plant, dc);
      const reversed = link(dc, plant);

      const sentence = absenceSentence(
        linkKey('PLANT-1', 'DC-1'),
        'Baseline v3',
        { element: null, nearMiss, reversed },
        (l: NetworkLink) => linkLabel(l, names),
      );

      expect(sentence).toContain('PLANT-1 → DC-1 is not in Baseline v3.');
      expect(sentence).toContain('differs only in case');
      expect(sentence).toContain('the other way round');
    });
  });

  describe('tallyMatches', () => {
    it('counts only the panes that have answered', () => {
      // Three panes loaded, the element in two of them; a fourth still reading is not in the list.
      expect(tallyMatches([true, false, true])).toEqual({ present: 2, of: 3 });
      expect(tallyMatches([])).toEqual({ present: 0, of: 0 });
    });
  });
});
