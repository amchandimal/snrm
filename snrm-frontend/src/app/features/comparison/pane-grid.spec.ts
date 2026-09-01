import {
  PANE_LIMIT,
  PANE_LIMIT_SENTENCE,
  PANE_MINIMUM,
  SUITE_EXPANDED_LIMIT,
  collapseAllControl,
  compareBlocker,
  overCapNote,
  paneGrid,
  paneIdsParam,
  parsePaneIds,
  suiteDefaultSentence,
  suitesExpandedByDefault,
} from './pane-grid';

/**
 * The side-by-side window's grid, its cap, and what the panes in it open as
 * (FR-25, FR-27).
 *
 * The two shapes the window guarantees - "two panes side by side, four as a 2 × 2" - are the first
 * two expectations, and the rest walk the rule out to the cap. Nothing here transcribes a sentence:
 * where a message is asserted it is asserted **against the exported constant**, so a reword fails
 * the test rather than the reader (the rule `network-selection.spec.ts` states for `archive-rules`).
 */
describe('pane-grid', () => {
  describe('paneGrid', () => {
    it('draws two networks left and right, and four as a 2 × 2', () => {
      expect(paneGrid(2)).toEqual({ columns: 2, rows: 1 });
      expect(paneGrid(4)).toEqual({ columns: 2, rows: 2 });
    });

    it('draws three as two over one', () => {
      // ⌈√3⌉ = 2 columns, ⌈3/2⌉ = 2 rows: two on the top row, one under the first.
      expect(paneGrid(3)).toEqual({ columns: 2, rows: 2 });
    });

    it('follows ⌈√n⌉ / ⌈n / columns⌉ up to the cap', () => {
      expect(paneGrid(1)).toEqual({ columns: 1, rows: 1 });
      expect(paneGrid(5)).toEqual({ columns: 3, rows: 2 });
      expect(paneGrid(6)).toEqual({ columns: 3, rows: 2 });
    });

    it('keeps a perfect square square', () => {
      // `Math.sqrt` is correctly rounded, so ⌈√4⌉ is 2 and not 3 - the one silent way this could
      // have been wrong.
      expect(paneGrid(9)).toEqual({ columns: 3, rows: 3 });
      expect(paneGrid(16)).toEqual({ columns: 4, rows: 4 });
    });

    it('answers 0 × 0 for nothing to draw, so no track is written', () => {
      expect(paneGrid(0)).toEqual({ columns: 0, rows: 0 });
      expect(paneGrid(-3)).toEqual({ columns: 0, rows: 0 });
      expect(paneGrid(Number.NaN)).toEqual({ columns: 0, rows: 0 });
    });

    it('draws the cap of twelve as four columns and three rows', () => {
      // The cost of the larger cap, as arithmetic - and the reason FR-27's collapse is what pays
      // for it. The grid rule itself is unchanged; only the number fed to it moved.
      expect(PANE_LIMIT).toBe(12);
      expect(paneGrid(PANE_LIMIT)).toEqual({ columns: 4, rows: 3 });
    });

    it('fills that grid at the cap, where the previous cap of ten left it ragged', () => {
      // The argument for the larger cap in two lines: ten and twelve draw the *same* grid, and twelve
      // is the count that has a pane in every cell of it. No new column, so no smaller label; no
      // new row, so nothing further down.
      expect(paneGrid(10)).toEqual(paneGrid(PANE_LIMIT));
      expect(paneGrid(PANE_LIMIT).columns * paneGrid(PANE_LIMIT).rows).toBe(PANE_LIMIT);
    });

    it('takes a fourth row at thirteen, which is what the cap now sits against', () => {
      expect(paneGrid(PANE_LIMIT + 1).rows).toBe(4);
    });

    it('takes a third row from seven panes on, which is what the old cap of six held off', () => {
      // The original argument, kept as arithmetic rather than as prose: six is the last count within
      // two rows. What changed later is that a third row of *collapsed* panes is readable.
      expect(paneGrid(6).rows).toBe(2);
      expect(paneGrid(7).rows).toBe(3);
    });
  });

  describe('suitesExpandedByDefault', () => {
    it('opens expanded up to six panes and collapsed above (FR-27)', () => {
      expect(suitesExpandedByDefault(2)).toBeTrue();
      expect(suitesExpandedByDefault(6)).toBeTrue();
      expect(suitesExpandedByDefault(7)).toBeFalse();
      expect(suitesExpandedByDefault(PANE_LIMIT)).toBeFalse();
    });

    it('hinges on the grid rule, so the threshold cannot drift away from the layout', () => {
      // The rule is "at most two rows", not "at most six panes" - asserted as the relationship
      // rather than as the number, which is the whole reason it is derived from `paneGrid`.
      for (let count = 1; count <= PANE_LIMIT; count += 1) {
        expect(suitesExpandedByDefault(count)).toBe(paneGrid(count).rows <= 2);
      }
    });

    it('states six as the largest count that opens expanded', () => {
      expect(SUITE_EXPANDED_LIMIT).toBe(6);
      expect(suitesExpandedByDefault(SUITE_EXPANDED_LIMIT)).toBeTrue();
      expect(suitesExpandedByDefault(SUITE_EXPANDED_LIMIT + 1)).toBeFalse();
    });

    it('says which way it went, in the sentence the window prints', () => {
      expect(suiteDefaultSentence(4)).toContain('showing its structural metrics');
      expect(suiteDefaultSentence(PANE_LIMIT)).toContain('title and a miniature');
    });
  });

  describe('collapseAllControl', () => {
    it('names the state it will produce, not the state the window is in', () => {
      // Nothing collapsed: pressing it collapses. Everything collapsed: pressing it expands.
      expect(collapseAllControl(4, 0).label).toBe('Collapse all');
      expect(collapseAllControl(4, 0).collapse).toBeTrue();
      expect(collapseAllControl(4, 4).label).toBe('Expand all');
      expect(collapseAllControl(4, 4).collapse).toBeFalse();
    });

    it('reads “Collapse all” in a mixed window, and collapses the rest', () => {
      // The rule that is worth pinning: one press always takes *every* pane somewhere, so the
      // reader is never left working out which half moved.
      for (let collapsed = 1; collapsed < PANE_LIMIT; collapsed += 1) {
        const control = collapseAllControl(PANE_LIMIT, collapsed);
        expect(control.label).toBe('Collapse all');
        expect(control.collapse).toBeTrue();
      }
    });

    it('says how many are already collapsed when only some are', () => {
      expect(collapseAllControl(12, 4).hint).toContain('4 of 12');
    });

    it('promises no request in either direction, since the suites are already held', () => {
      expect(collapseAllControl(4, 4).hint).toContain('re-read');
      expect(collapseAllControl(4, 0).hint).toContain('no request');
    });

    it('answers an empty window rather than throwing at its caller', () => {
      // Zero collapsed of zero is not "every pane is collapsed": there is no pane. The window does
      // not render the control at all here, and the rule still has an answer.
      expect(collapseAllControl(0, 0).label).toBe('Collapse all');
    });

    it('is not confused by counts that arrived wrong', () => {
      expect(collapseAllControl(4, 9).label).toBe('Expand all');
      expect(collapseAllControl(Number.NaN, Number.NaN).label).toBe('Collapse all');
      expect(collapseAllControl(-2, -2).label).toBe('Collapse all');
    });
  });

  describe('parsePaneIds', () => {
    it('reads the comma-separated form this application writes, in the URL’s order', () => {
      expect(parsePaneIds('7,3,11').ids).toEqual([7, 3, 11]);
    });

    it('round-trips what paneIdsParam writes', () => {
      const ids = [4, 9, 2];
      expect(parsePaneIds(paneIdsParam(ids)).ids).toEqual(ids);
    });

    it('reads the repeated form a router may hand back', () => {
      expect(parsePaneIds(['3', '4']).ids).toEqual([3, 4]);
    });

    it('answers an absent parameter with nothing selected', () => {
      expect(parsePaneIds(null).ids).toEqual([]);
      expect(parsePaneIds(undefined).ids).toEqual([]);
      expect(parsePaneIds('').ids).toEqual([]);
    });

    it('folds a repeated id into one pane and counts it', () => {
      const parsed = parsePaneIds('5,5,6');
      expect(parsed.ids).toEqual([5, 6]);
      expect(parsed.duplicates).toBe(1);
    });

    it('keeps a malformed token verbatim rather than guessing a number out of it', () => {
      const parsed = parsePaneIds('3,3abc,,-1,0,x');
      expect(parsed.ids).toEqual([3]);
      expect(parsed.malformed).toEqual(['3abc', '-1', '0', 'x']);
    });

    it('caps at the limit and hands back what it dropped', () => {
      // Written against `PANE_LIMIT` rather than against a hand-typed run of ids, because the cap
      // has moved twice (six → ten → twelve) and a literal list is the thing that silently stops
      // testing the cap when it moves again.
      const parsed = parsePaneIds(paneIdsParam(idsUpTo(PANE_LIMIT + 2)));
      expect(parsed.ids.length).toBe(PANE_LIMIT);
      expect(parsed.ids).toEqual(idsUpTo(PANE_LIMIT));
      expect(parsed.dropped).toEqual([PANE_LIMIT + 1, PANE_LIMIT + 2]);
    });

    it('draws all twelve of the raised cap rather than the six or ten before it', () => {
      expect(parsePaneIds(paneIdsParam(idsUpTo(12))).dropped).toEqual([]);
      expect(parsePaneIds(paneIdsParam(idsUpTo(12))).ids.length).toBe(12);
    });

    it('tolerates a hand-edited link with spaces around the ids', () => {
      expect(parsePaneIds(' 3 , 4 ').ids).toEqual([3, 4]);
    });
  });

  describe('overCapNote', () => {
    it('says nothing about a selection it drew whole', () => {
      expect(overCapNote(parsePaneIds('3,4'))).toBeNull();
    });

    it('says nothing about a selection at the raised cap exactly', () => {
      expect(overCapNote(parsePaneIds(paneIdsParam(idsUpTo(PANE_LIMIT))))).toBeNull();
    });

    it('names what it did not draw, and states the cap in its one wording', () => {
      const note = overCapNote(parsePaneIds(paneIdsParam(idsUpTo(PANE_LIMIT + 1))));
      expect(note).toContain(`#${PANE_LIMIT + 1}`);
      expect(note).toContain(PANE_LIMIT_SENTENCE);
    });

    it('reports all three faults of one bad paste in one sentence', () => {
      const overage = PANE_LIMIT + 1;
      const note =
        overCapNote(parsePaneIds(`1,1,${paneIdsParam(idsUpTo(overage))},nope`)) ?? '';
      expect(note).toContain(`#${overage}`);
      expect(note).toContain('repeated');
      expect(note).toContain('“nope”');
    });
  });

  describe('compareBlocker', () => {
    it('refuses fewer than two, because one network beside nothing is not a comparison', () => {
      expect(compareBlocker(0)).not.toBeNull();
      expect(compareBlocker(PANE_MINIMUM - 1)).not.toBeNull();
    });

    it('offers the action from two networks to the cap', () => {
      expect(compareBlocker(PANE_MINIMUM)).toBeNull();
      expect(compareBlocker(PANE_LIMIT)).toBeNull();
    });

    it('states the cap and how many to untick, in the wording the window also uses', () => {
      const blocker = compareBlocker(PANE_LIMIT + 3) ?? '';
      expect(blocker).toContain(PANE_LIMIT_SENTENCE);
      expect(blocker).toContain('Untick 3');
    });

    it('offers the seventh through twelfth networks the earlier caps refused', () => {
      // The visible half of the amendments: seven was blocked by the old cap of six, eleven and
      // twelve by the cap of ten, and all of them are the ordinary case now.
      expect(compareBlocker(7)).toBeNull();
      expect(compareBlocker(10)).toBeNull();
      expect(compareBlocker(12)).toBeNull();
      expect(compareBlocker(13)).not.toBeNull();
    });
  });
});

/** `[1, 2, … n]` - a selection of the right size, built from the cap rather than typed out. */
function idsUpTo(count: number): number[] {
  return Array.from({ length: count }, (_unused, index) => index + 1);
}
