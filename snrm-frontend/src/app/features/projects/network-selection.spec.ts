import { Id, Network, ProblemCode } from '../../core/models';
import {
  ARCHIVE_IS_A_COPY,
  RESTORE_CREATES_A_NEW_PROJECT,
  ruleSentence,
} from './archive-rules';
import {
  DeletionOutcome,
  deletionDetails,
  deletionHeadline,
  frozenSelectionBlocker,
  outcomeNote,
  reconcileSelection,
  selectionDeleteConfirm,
  selectionExportConfirm,
  selectionOf,
  splitSelection,
  toggled,
} from './network-selection';

function network(id: number, name: string, version: number, editable = true): Network {
  return {
    id,
    projectId: 1,
    name,
    version,
    baseline: false,
    editable,
    periodLength: { value: 1, unit: 'DAY' },
    horizonPeriods: 30,
    roundingPolicy: 'UP',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

const ids = (selected: ReadonlySet<Id>) => [...selected].sort((a, b) => a - b);
const labels = (networks: readonly Network[]) =>
  networks.map((entry) => `${entry.name} v${entry.version}`);

function outcome(
  entry: Network,
  status: 'deleted' | 'refused',
  code: string | null = null,
): DeletionOutcome {
  return {
    network: entry,
    status,
    message: status === 'refused' ? 'The server said no.' : null,
    code,
  };
}

describe('network-selection', () => {
  // ---------------------------------------------------------------- reconciliation (FR-23)

  describe('reconcileSelection', () => {
    it('drops a network that is no longer in the list', () => {
      const list = [network(1, 'Baseline', 1), network(3, 'Buffer', 1)];

      const next = reconcileSelection(new Set([1, 2, 3]), list);

      // #2 was deleted - in this tab or another - so it leaves the selection with the row.
      expect(ids(next)).toEqual([1, 3]);
    });

    it('returns the same set instance when every selected network is still there', () => {
      const list = [network(1, 'Baseline', 1), network(2, 'Buffer', 1)];
      const selected = new Set([1, 2]);

      // Identity, not equality: this runs on every list read, and a new Set each time would be a
      // change to every signal derived from it.
      expect(reconcileSelection(selected, list)).toBe(selected);
    });

    it('returns the same empty set rather than building one', () => {
      const selected = new Set<Id>();

      expect(reconcileSelection(selected, [network(1, 'Baseline', 1)])).toBe(selected);
    });

    it('empties a selection when the list it was made against has gone', () => {
      // The project changed, or the reload came back with nothing. Nothing survives, and nothing
      // can be aimed at a row.
      expect(ids(reconcileSelection(new Set([1, 2]), []))).toEqual([]);
    });

    it('does not resurrect an id that is in the list but was never selected', () => {
      const list = [network(1, 'Baseline', 1), network(2, 'Buffer', 1)];

      expect(ids(reconcileSelection(new Set([2]), list))).toEqual([2]);
    });
  });

  describe('selectionOf', () => {
    it('answers in list order, not selection order', () => {
      const list = [network(1, 'Baseline', 1), network(2, 'Buffer', 1), network(3, 'Dual', 1)];

      expect(labels(selectionOf(list, new Set([3, 1])))).toEqual(['Baseline v1', 'Dual v1']);
    });

    it('never answers with an id the list does not carry', () => {
      const list = [network(1, 'Baseline', 1)];

      expect(labels(selectionOf(list, new Set([1, 99])))).toEqual(['Baseline v1']);
    });
  });

  describe('toggled', () => {
    it('adds and removes', () => {
      expect(ids(toggled(new Set([1]), 2, true))).toEqual([1, 2]);
      expect(ids(toggled(new Set([1, 2]), 1, false))).toEqual([2]);
    });

    it('returns the same instance when the id is already in that state', () => {
      const selected = new Set([1]);

      expect(toggled(selected, 1, true)).toBe(selected);
      expect(toggled(selected, 2, false)).toBe(selected);
    });
  });

  // ---------------------------------------------------- the deletable / blocked split (FR-15)

  describe('splitSelection', () => {
    it('splits on `editable` and keeps list order in both halves', () => {
      const split = splitSelection([
        network(1, 'Baseline', 1, false),
        network(2, 'Buffer', 1),
        network(3, 'Dual', 1, false),
        network(4, 'Lateral', 1),
      ]);

      expect(labels(split.deletable)).toEqual(['Buffer v1', 'Lateral v1']);
      expect(labels(split.blocked)).toEqual(['Baseline v1', 'Dual v1']);
    });

    it('answers two empty halves for an empty selection', () => {
      const split = splitSelection([]);

      expect(split.deletable).toEqual([]);
      expect(split.blocked).toEqual([]);
    });
  });

  describe('frozenSelectionBlocker', () => {
    it('names the networks and FR-20 when every selected network is frozen', () => {
      const split = splitSelection([
        network(1, 'Baseline', 1, false),
        network(2, 'Buffer', 2, false),
      ]);

      const sentence = frozenSelectionBlocker(split)!;

      expect(sentence).toContain('All 2 selected networks are');
      expect(sentence).toContain('Baseline v1 (#1)');
      expect(sentence).toContain('Buffer v2 (#2)');
      // The remedy, not just the refusal.
      expect(sentence).toContain('FR-20');
    });

    it('is null as soon as one selected network can go', () => {
      const split = splitSelection([network(1, 'Baseline', 1, false), network(2, 'Buffer', 1)]);

      expect(frozenSelectionBlocker(split)).toBeNull();
    });

    it('is null for an empty selection - there is nothing to explain', () => {
      expect(frozenSelectionBlocker(splitSelection([]))).toBeNull();
    });
  });

  // ------------------------------------------------------------- the confirmation (FR-23)

  // --------------------------- one wording for the row delete and the set delete (FR-15, FR-26)

  describe('deletionDetails', () => {
    it('agrees with the count rather than hedging', () => {
      expect(deletionDetails(1)[0]).toContain('Every node and link in it');
      expect(deletionDetails(3)[0]).toContain('Every node and link in each network');
    });

    it('warns about the fork note of a network that is staying, at both counts', () => {
      // Deleting `Baseline v1` drops the variant record of `Baseline v2`, which is not going - an
      // edge disappears from the lineage that nothing else on the confirmation would mention.
      for (const count of [1, 4]) {
        expect(deletionDetails(count)[1]).toContain('a network that is staying');
      }
    });

    it('says products are not deleted, in one wording', () => {
      expect(deletionDetails(1)[2]).toBe(deletionDetails(9)[2]);
      expect(deletionDetails(1)[2]).toContain('project-scoped');
    });

    it('is what the set confirmation lists, so the row and the set cannot drift (FR-26)', () => {
      // FR-26 moved the per-row Delete into the row menu, and the rule that governs
      // `core/run-discard.ts` applies: one irreversible act must not be described two ways. The
      // dashboard's per-row dialog renders `deletionDetails(1)`; this is the set dialog rendering
      // the same lines, asserted against the function rather than against copied strings.
      const split = splitSelection([network(1, 'A', 1), network(2, 'B', 1)]);

      expect(selectionDeleteConfirm('P', split).details.slice(0, 3)).toEqual([
        ...deletionDetails(2),
      ]);
      expect(
        selectionDeleteConfirm('P', splitSelection([network(1, 'A', 1)])).details.slice(0, 3),
      ).toEqual([...deletionDetails(1)]);
    });
  });

  describe('selectionDeleteConfirm', () => {
    it('names both groups and their counts when the selection is mixed', () => {
      const split = splitSelection([
        network(1, 'Baseline', 1, false),
        network(2, 'Buffer', 1),
        network(3, 'Dual', 1),
      ]);

      const confirm = selectionDeleteConfirm('Archive Test', split);

      expect(confirm.title).toBe('Delete 2 of the 3 selected networks?');
      expect(confirm.deletableHeading).toBe('These 2 will be deleted');
      expect(confirm.blockedHeading).toBe('This one is frozen and will not be deleted');
      expect(confirm.message).toContain('One more selected network is');
      expect(confirm.confirmLabel).toBe('Delete 2 networks');
    });

    it('offers FR-20’s discard as the way out of the frozen group', () => {
      const split = splitSelection([network(1, 'Baseline', 1, false), network(2, 'Buffer', 1)]);

      const remedy = selectionDeleteConfirm('Archive Test', split).blockedRemedy!;

      expect(remedy).toContain('discard its runs');
      expect(remedy).toContain('FR-20');
    });

    it('says nothing about a frozen group when there is not one', () => {
      const split = splitSelection([network(1, 'Buffer', 1), network(2, 'Dual', 1)]);

      const confirm = selectionDeleteConfirm('Archive Test', split);

      expect(confirm.title).toBe('Delete these 2 networks?');
      expect(confirm.blockedHeading).toBeNull();
      expect(confirm.blockedRemedy).toBeNull();
      expect(confirm.message).not.toContain('frozen');
    });

    it('reads in the singular for one deletable network', () => {
      const confirm = selectionDeleteConfirm('Archive Test', splitSelection([network(1, 'B', 1)]));

      expect(confirm.title).toBe('Delete this network?');
      expect(confirm.deletableHeading).toBe('This will be deleted');
      expect(confirm.confirmLabel).toBe('Delete 1 network');
    });

    it('asks for the owning project’s name, unchanged from the single-network dialog (FR-15)', () => {
      const split = splitSelection([network(1, 'Baseline', 1), network(2, 'Baseline', 2)]);

      const confirm = selectionDeleteConfirm('Archive Test', split);

      // Not a phrase derived from the selection: two variants share a name, and a phrase that
      // changed with every set is a check nobody reads (FR-15).
      expect(confirm.requiredPhrase).toBe('Archive Test');
      expect(confirm.phraseLabel).toBe('project name');
    });

    it('passes an unknown project name through as blank, which the dialog refuses to satisfy', () => {
      const confirm = selectionDeleteConfirm('', splitSelection([network(1, 'B', 1)]));

      // `shared/confirm-dialog` treats a present-but-blank phrase as unsatisfiable, so the action
      // cannot be enabled while the project is still loading.
      expect(confirm.requiredPhrase).toBe('');
    });

    it('states that the deletes are issued one at a time and reported per network', () => {
      const split = splitSelection([network(1, 'A', 1), network(2, 'B', 1), network(3, 'C', 1)]);

      expect(selectionDeleteConfirm('P', split).details.join(' ')).toContain(
        'one network at a time',
      );
    });
  });

  // ------------------------------------------ exporting a selection as a project (FR-24)

  describe('selectionExportConfirm', () => {
    const three = [network(1, 'Alpha', 1), network(2, 'Bravo', 1), network(3, 'Charlie', 2)];

    it('states the two rules verbatim from archive-rules, not reworded', () => {
      const details = selectionExportConfirm(three).details;

      // The point of `archive-rules.ts`: the restore card on the project list makes the same two
      // claims, and a version of them that drifted would read as two different rules. Compared
      // against the constants rather than against a copied string, so a reword fails here.
      expect(details).toEqual([
        ruleSentence(ARCHIVE_IS_A_COPY),
        ruleSentence(RESTORE_CREATES_A_NEW_PROJECT),
      ]);
      expect(details[0]).toContain('copies and never moves');
      expect(details[1]).toContain('creates a new project');
      expect(details[1]).toContain('merge');
    });

    it('counts the selection in its title, its list heading and its button', () => {
      const confirm = selectionExportConfirm(three);

      expect(confirm.title).toBe('Export these 3 networks as a project?');
      expect(confirm.listHeading).toBe('These 3 networks will travel');
      expect(confirm.confirmLabel).toBe('Export 3 networks');
    });

    it('reads in the singular for one network', () => {
      const confirm = selectionExportConfirm([network(1, 'Alpha', 1)]);

      expect(confirm.title).toBe('Export this network as a project?');
      expect(confirm.listHeading).toBe('This network will travel');
      expect(confirm.confirmLabel).toBe('Export 1 network');
      expect(confirm.message).toContain('holding exactly it');
    });

    it('names all three carry rules, including the two that carry more than was ticked', () => {
      const carries = selectionExportConfirm(three).carries.join(' ');

      // A restored project holding a product nothing uses reads as a defect unless it was
      // announced; a scenario that refuses to run reads as one especially if it was not.
      expect(carries).toContain('whole product catalogue');
      expect(carries).toContain('an unused entry is harmless');
      expect(carries).toContain('Every disruption scenario');
      expect(carries).toContain('visibly refuses to run');
      expect(carries).toContain('Fork notes whose parent network you did not tick are dropped');
    });

    it('mentions the runs of a frozen network, because that is what a copy carries', () => {
      const mixed = [network(1, 'Alpha', 1), network(2, 'Bravo', 1, false)];

      expect(selectionExportConfirm(mixed).carries[0]).toContain('the frozen one');
      expect(selectionExportConfirm(mixed).carries[0]).toContain('metric results and time series');
    });

    it('says nothing about runs when nothing selected has any', () => {
      expect(selectionExportConfirm(three).carries[0]).not.toContain('frozen');
    });

    it('asks for no typed phrase - a copy is not an irreversible act', () => {
      // Deliberately absent from the interface rather than null on it: spending FR-15's typed
      // discipline on a download would teach the user to type through it.
      expect('requiredPhrase' in selectionExportConfirm(three)).toBe(false);
    });
  });

  // ------------------------------------------------------------- the outcome report (FR-23)

  describe('deletionHeadline', () => {
    const a = network(1, 'A', 1);
    const b = network(2, 'B', 1);
    const c = network(3, 'C', 1);

    it('reports a clean run', () => {
      expect(deletionHeadline([outcome(a, 'deleted'), outcome(b, 'deleted')])).toBe(
        'All 2 networks deleted.',
      );
      expect(deletionHeadline([outcome(a, 'deleted')])).toBe('1 network deleted.');
    });

    it('names what did not go as well as what did', () => {
      const headline = deletionHeadline([
        outcome(a, 'deleted'),
        outcome(b, 'refused', ProblemCode.NETWORK_IMMUTABLE),
        outcome(c, 'deleted'),
      ]);

      expect(headline).toBe('2 of 3 networks deleted; 1 was refused and is still here.');
    });

    it('does not claim success for the set when every one was refused', () => {
      expect(
        deletionHeadline([
          outcome(a, 'refused', ProblemCode.NETWORK_IMMUTABLE),
          outcome(b, 'refused', ProblemCode.NETWORK_IMMUTABLE),
        ]),
      ).toBe('None of the 2 networks was deleted - the server refused every one.');
    });

    it('describes a run that stopped part-way by what it actually did', () => {
      // Two of five attempted before the sequence was abandoned: the headline counts the outcomes
      // it has, never the size of the selection it started from.
      const headline = deletionHeadline([outcome(a, 'deleted'), outcome(b, 'refused', null)]);

      expect(headline).toBe('1 of 2 networks deleted; 1 was refused and is still here.');
    });

    it('answers for an empty run', () => {
      expect(deletionHeadline([])).toBe('Nothing was deleted.');
    });
  });

  describe('outcomeNote', () => {
    const a = network(1, 'A', 1);

    it('explains a freeze that landed between the list and the request', () => {
      const note = outcomeNote(outcome(a, 'refused', ProblemCode.NETWORK_IMMUTABLE))!;

      expect(note).toContain('between the list being read and the request being sent');
      expect(note).toContain('FR-20');
    });

    it('explains a network something else deleted first', () => {
      expect(outcomeNote(outcome(a, 'refused', ProblemCode.NOT_FOUND))).toContain(
        'already gone',
      );
    });

    it('adds nothing to a code it has no remedy for - the server’s sentence stands alone', () => {
      expect(outcomeNote(outcome(a, 'refused', 'SOMETHING_NEW'))).toBeNull();
      expect(outcomeNote(outcome(a, 'refused', null))).toBeNull();
      expect(outcomeNote(outcome(a, 'deleted'))).toBeNull();
    });
  });
});
