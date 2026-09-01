import { NodeType } from '../../core/models';
import {
  LinkRefusal,
  LinkWarning,
  linkPairKey,
  linkVerdict,
  nextNodeName,
  warningForLink,
} from './echelon-rules';

/**
 * Every row of the validity table.
 *
 * These rules are the one piece of editor logic that has to be right - a wrong verdict either
 * refuses a topology the researcher needs or silently permits one the model cannot interpret - so
 * they are exhaustive over the 4 × 4 type pairs rather than illustrative.
 */
describe('echelon rules', () => {
  const NONE = new Set<string>();
  const endpoint = (id: number, type: NodeType) => ({ id, type });

  describe('linkVerdict - refusals', () => {
    it('refuses a self-loop before anything else', () => {
      const verdict = linkVerdict(endpoint(1, 'DC'), endpoint(1, 'DC'), NONE);
      expect(verdict.ok).toBeFalse();
      expect(verdict.ok || verdict.reason).toBe(LinkRefusal.SELF_LOOP);
    });

    it('refuses a pair that is already connected in that direction', () => {
      const existing = new Set([linkPairKey(1, 2)]);
      const verdict = linkVerdict(endpoint(1, 'PLANT'), endpoint(2, 'DC'), existing);
      expect(verdict.ok).toBeFalse();
      expect(verdict.ok || verdict.reason).toBe(LinkRefusal.DUPLICATE);
    });

    it('allows the reverse of an existing pair - arcs are ordered', () => {
      const existing = new Set([linkPairKey(1, 2)]);
      expect(linkVerdict(endpoint(2, 'DC'), endpoint(1, 'PLANT'), existing).ok).toBeTrue();
    });

    for (const source of ['PLANT', 'DC'] as NodeType[]) {
      it(`refuses ${source} → SUPPLIER`, () => {
        const verdict = linkVerdict(endpoint(1, source), endpoint(2, 'SUPPLIER'), NONE);
        expect(verdict.ok).toBeFalse();
        expect(verdict.ok || verdict.reason).toBe(LinkRefusal.INTO_SUPPLIER);
      });
    }

    it('refuses SUPPLIER → SUPPLIER as an inbound link, not as a lateral one', () => {
      const verdict = linkVerdict(endpoint(1, 'SUPPLIER'), endpoint(2, 'SUPPLIER'), NONE);
      expect(verdict.ok || verdict.reason).toBe(LinkRefusal.INTO_SUPPLIER);
    });

    for (const target of ['PLANT', 'DC', 'CUSTOMER'] as NodeType[]) {
      it(`refuses CUSTOMER → ${target}`, () => {
        const verdict = linkVerdict(endpoint(1, 'CUSTOMER'), endpoint(2, target), NONE);
        expect(verdict.ok).toBeFalse();
        expect(verdict.ok || verdict.reason).toBe(LinkRefusal.OUT_OF_CUSTOMER);
      });
    }

    it('reports CUSTOMER → SUPPLIER as the outbound rule, the end the user dragged from', () => {
      const verdict = linkVerdict(endpoint(1, 'CUSTOMER'), endpoint(2, 'SUPPLIER'), NONE);
      expect(verdict.ok || verdict.reason).toBe(LinkRefusal.OUT_OF_CUSTOMER);
    });
  });

  describe('linkVerdict - the textbook forward arcs carry no warning', () => {
    const forward: [NodeType, NodeType][] = [
      ['SUPPLIER', 'PLANT'],
      ['PLANT', 'DC'],
      ['DC', 'CUSTOMER'],
    ];
    for (const [source, target] of forward) {
      it(`${source} → ${target}`, () => {
        const verdict = linkVerdict(endpoint(1, source), endpoint(2, target), NONE);
        expect(verdict.ok).toBeTrue();
        expect(verdict.ok && verdict.warning).toBeUndefined();
      });
    }
  });

  describe('linkVerdict - unconventional but legal arcs warn', () => {
    it('flags DC → DC as lateral transshipment', () => {
      const verdict = linkVerdict(endpoint(1, 'DC'), endpoint(2, 'DC'), NONE);
      expect(verdict.ok).toBeTrue();
      expect(verdict.ok && verdict.warning).toBe(LinkWarning.LATERAL);
    });

    it('flags PLANT → PLANT as lateral', () => {
      const verdict = linkVerdict(endpoint(1, 'PLANT'), endpoint(2, 'PLANT'), NONE);
      expect(verdict.ok && verdict.warning).toBe(LinkWarning.LATERAL);
    });

    it('flags DC → PLANT as upstream', () => {
      const verdict = linkVerdict(endpoint(1, 'DC'), endpoint(2, 'PLANT'), NONE);
      expect(verdict.ok).toBeTrue();
      expect(verdict.ok && verdict.warning).toBe(LinkWarning.UPSTREAM);
    });

    it('flags SUPPLIER → DC as skipping an echelon', () => {
      const verdict = linkVerdict(endpoint(1, 'SUPPLIER'), endpoint(2, 'DC'), NONE);
      expect(verdict.ok && verdict.warning).toBe(LinkWarning.SKIPS_ECHELON);
    });

    it('flags SUPPLIER → CUSTOMER as skipping an echelon', () => {
      const verdict = linkVerdict(endpoint(1, 'SUPPLIER'), endpoint(2, 'CUSTOMER'), NONE);
      expect(verdict.ok && verdict.warning).toBe(LinkWarning.SKIPS_ECHELON);
    });

    it('flags PLANT → CUSTOMER as skipping an echelon (direct shipment)', () => {
      const verdict = linkVerdict(endpoint(1, 'PLANT'), endpoint(2, 'CUSTOMER'), NONE);
      expect(verdict.ok && verdict.warning).toBe(LinkWarning.SKIPS_ECHELON);
    });
  });

  describe('warningForLink mirrors the verdict for links that already exist', () => {
    it('is null only for a single forward step', () => {
      expect(warningForLink('SUPPLIER', 'PLANT')).toBeNull();
      expect(warningForLink('PLANT', 'DC')).toBeNull();
      expect(warningForLink('DC', 'CUSTOMER')).toBeNull();
    });

    it('re-derives the badge after a node is retyped', () => {
      // The exact case the derived-not-stored design exists for: PLANT → DC is clean, and retyping
      // the source to DC turns the same arc lateral without anyone touching the link.
      expect(warningForLink('PLANT', 'DC')).toBeNull();
      expect(warningForLink('DC', 'DC')).toBe(LinkWarning.LATERAL);
    });
  });

  describe('nextNodeName', () => {
    it('starts at 1 for an empty network', () => {
      expect(nextNodeName('DC', [])).toBe('DC 1');
    });

    it('skips names already taken, so the drop cannot 409 on DUPLICATE_NAME', () => {
      expect(nextNodeName('SUPPLIER', ['Supplier 1', 'Supplier 2'])).toBe('Supplier 3');
    });

    it('fills a gap rather than always appending', () => {
      expect(nextNodeName('PLANT', ['Plant 1', 'Plant 3'])).toBe('Plant 2');
    });

    it('compares case-insensitively - the backend uses a case-insensitive collation', () => {
      expect(nextNodeName('DC', ['dc 1'])).toBe('DC 2');
    });
  });
});
