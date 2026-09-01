import { Network, ProblemCode } from '../../core/models';
import {
  NETWORK_NAME_MAX,
  renameBlocker,
  renameConfirm,
  renameOutcome,
  renameRefusalNote,
  renameRequest,
} from './network-rename';
import { describeNetwork } from './network-selection';

function network(
  id: number,
  name: string,
  version: number,
  { baseline = false, editable = true } = {},
): Network {
  return {
    id,
    projectId: 1,
    name,
    version,
    baseline,
    editable,
    periodLength: { value: 1, unit: 'DAY' },
    horizonPeriods: 30,
    roundingPolicy: 'UP',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

describe('network-rename', () => {
  // ------------------------------------------------------------------ the trap (FR-29)

  describe('renameRequest - name and baseline flag together', () => {
    it('sends the baseline flag as true when the row holds it', () => {
      // The whole feature. `PUT /networks/{id}` replaces both fields, and `baseline` is a primitive
      // on the backend - a body without it arrives as false and un-baselines the project.
      const request = renameRequest(network(7, 'Baseline_v3_FINAL', 1, { baseline: true }), 'EU dual source');

      expect(request).toEqual({ name: 'EU dual source', baseline: true });
    });

    it('sends the baseline flag as false when the row does not hold it', () => {
      const request = renameRequest(network(7, 'Sketch', 1, { baseline: false }), 'Buffered');

      expect(request).toEqual({ name: 'Buffered', baseline: false });
    });

    it('always carries the flag as a property, never omits it', () => {
      // `'baseline' in request` rather than a truthiness check: an omitted key and an explicit
      // false are the same value to `===` and opposite things on the wire.
      expect('baseline' in renameRequest(network(7, 'Sketch', 1), 'Buffered')).toBeTrue();
    });

    it('takes the flag from the network and never from the typed name', () => {
      const baseline = network(7, 'Baseline', 1, { baseline: true });

      // Whatever is typed, the flag is the row's. There is no control that could set it here, and
      // this is the assertion that fails if one is ever added.
      expect(renameRequest(baseline, '').baseline).toBeTrue();
      expect(renameRequest(baseline, 'Anything at all').baseline).toBeTrue();
    });

    it('trims the name, so the string checked here is the string the server checks', () => {
      // `@Size(max = 160)` is evaluated before `NetworkService.update` trims, so an untrimmed name
      // could pass this client's own length check and be refused with a 400.
      expect(renameRequest(network(7, 'Sketch', 1), '  Baseline  ').name).toBe('Baseline');
    });
  });

  // ------------------------------------------------------- validation, matching the server's

  describe('renameBlocker', () => {
    const row = network(7, 'Baseline_v3_FINAL', 1);

    it('allows a different, non-empty name', () => {
      expect(renameBlocker(row, 'Dual sourcing, EU')).toBeNull();
    });

    it('refuses an empty name, and one that is only whitespace', () => {
      // `@NotBlank` on the server. Both are the same string once trimmed.
      expect(renameBlocker(row, '')?.invalid).toBeTrue();
      expect(renameBlocker(row, '   ')?.invalid).toBeTrue();
    });

    it('allows exactly the maximum length and refuses one character more', () => {
      expect(renameBlocker(row, 'x'.repeat(NETWORK_NAME_MAX))).toBeNull();
      expect(renameBlocker(row, 'x'.repeat(NETWORK_NAME_MAX + 1))?.invalid).toBeTrue();
    });

    it('measures length after trimming, as the request does', () => {
      expect(renameBlocker(row, ` ${'x'.repeat(NETWORK_NAME_MAX)} `)).toBeNull();
    });

    it('says how many characters over the limit a long name is', () => {
      expect(renameBlocker(row, 'x'.repeat(NETWORK_NAME_MAX + 4))?.reason).toContain('by 4');
    });

    it('blocks the name it already has, but not as an error', () => {
      // The dialog opens prefilled with the current name, so this is the state it starts in: the
      // action is unavailable and the reason is a note, not a red field.
      const unchanged = renameBlocker(row, 'Baseline_v3_FINAL');

      expect(unchanged).not.toBeNull();
      expect(unchanged?.invalid).toBeFalse();
    });

    it('treats a name that differs only in surrounding space as unchanged', () => {
      expect(renameBlocker(row, '  Baseline_v3_FINAL  ')?.invalid).toBeFalse();
    });

    it('treats a name that differs only in case as a rename', () => {
      // Exact comparison, like `confirm-dialog`'s phrase check: `uq_network` is what decides
      // whether the server accepts it, and nothing here re-implements that.
      expect(renameBlocker(row, 'baseline_v3_final')).toBeNull();
    });

    it('never blocks a duplicate name', () => {
      // Deliberately: uniqueness is `uq_network (project_id, name, version)`, the server's to
      // enforce. A client-side check would be a second implementation of it, free to disagree.
      // Nothing in this module's signature can even see the rest of the project's list.
      expect(renameBlocker(network(7, 'Sketch', 1), 'Baseline')).toBeNull();
    });
  });

  // -------------------------------------------------------------------- what the dialog says

  describe('renameConfirm', () => {
    const row = network(7, 'Baseline_v3_FINAL', 1);

    it('names the network it will rename, with its version and id', () => {
      expect(renameConfirm(row).message).toContain(describeNetwork(row));
    });

    it('promises the researcher is not navigated away', () => {
      expect(renameConfirm(row).message).toContain('stay on this page');
    });

    it('says the baseline flag travels with the name, on a network that holds it', () => {
      const details = renameConfirm(network(7, 'Baseline', 1, { baseline: true })).details.join(' ');

      expect(details).toContain('stays the baseline');
      expect(details).toContain('replaced together');
    });

    it('says the baseline flag travels with the name, on a network that does not', () => {
      // Both branches say it. The reader cannot check what is on the wire, and "we sent the flag
      // you can see" is only reassuring if it is said when the flag is off as well.
      const details = renameConfirm(row).details.join(' ');

      expect(details).toContain('sent unchanged');
      expect(details).toContain('replaced together');
    });

    it('states that structure, runs and results are untouched', () => {
      const details = renameConfirm(row).details.join(' ');

      expect(details).toContain('nodes, links and per-product rows');
      expect(details).toContain('runs and results');
    });

    it('states that the version number does not move, and names it', () => {
      expect(renameConfirm(network(7, 'Baseline', 3)).details.join(' ')).toContain('stays v3');
    });

    it('says the name is not checked for uniqueness before sending, and what decides', () => {
      const details = renameConfirm(row).details.join(' ');

      expect(details).toContain('does not have to be unique');
      expect(details).toContain('the server is the one');
    });

    it('names the current name and the length limit in the field hint', () => {
      const hint = renameConfirm(row).nameHint;

      expect(hint).toContain('Baseline_v3_FINAL');
      expect(hint).toContain(String(NETWORK_NAME_MAX));
    });

    it('says a frozen network renames like any other, and why', () => {
      const frozen = renameConfirm(network(9, 'Baseline', 3, { editable: false })).details.join(' ');

      expect(frozen).toContain('frozen');
      expect(frozen).toContain('renames like any other');
      // The freeze covers what a result was computed *from*.
      expect(frozen).toContain('computed *from*');
      expect(frozen).toContain('nothing to fork and nothing to discard');
    });

    it('never describes a rename as an edit, frozen or not', () => {
      // FR-29 is explicit that a rename is not a structural edit, and the dialog must not imply
      // otherwise on the one row where a reader is primed to believe it.
      const editable = renameConfirm(row);
      const frozen = renameConfirm(network(9, 'Baseline', 3, { editable: false }));

      for (const prompt of [editable, frozen]) {
        const words = [prompt.title, prompt.message, ...prompt.details].join(' ').toLowerCase();
        expect(words).not.toContain('edit this');
        expect(words).not.toContain('editing this network');
      }
    });

    it('says nothing about the freeze on an editable network', () => {
      expect(renameConfirm(row).details.join(' ')).not.toContain('frozen');
    });

    it('has no typed phrase, and the shape has nowhere to put one', () => {
      // Nothing is destroyed and nothing structural changes: FR-15's typed discipline is sized to
      // an act with no undo, and a rename is undone by renaming it back.
      expect('requiredPhrase' in renameConfirm(row)).toBeFalse();
    });
  });

  // ------------------------------------------------------------------ what it says afterwards

  describe('renameOutcome', () => {
    const before = network(7, 'Baseline_v3_FINAL', 1);

    it('names the row as it was and as it came back', () => {
      const after = network(7, 'Dual sourcing, EU', 1);

      const sentence = renameOutcome(before, after);

      expect(sentence).toContain(describeNetwork(before));
      expect(sentence).toContain(describeNetwork(after));
    });

    it('says the row has moved, because the table sorts by name', () => {
      expect(renameOutcome(before, network(7, 'Dual sourcing, EU', 1))).toContain('has moved');
    });

    it('confirms the baseline flag survived, on a network that holds it', () => {
      const kept = renameOutcome(
        network(7, 'Baseline_v3_FINAL', 1, { baseline: true }),
        network(7, 'EU baseline', 1, { baseline: true }),
      );

      expect(kept).toContain('still the project’s baseline');
    });

    it('says the flag is unchanged on a network that does not hold it', () => {
      expect(renameOutcome(before, network(7, 'Buffered', 1))).toContain('unchanged');
    });

    it('reads the version from the answer, not from the request', () => {
      // `PUT /networks/{id}` does not renumber today. Reporting the response rather than assuming
      // is what keeps this sentence true if that ever changes.
      const renumbered = renameOutcome(before, network(7, 'Baseline', 4));

      expect(renumbered).toContain('v1 to v4');
    });

    it('says nothing about a version that did not move', () => {
      expect(renameOutcome(before, network(7, 'Buffered', 1))).not.toContain('version number moved');
    });

    it('says a frozen network is still frozen and its runs are untouched', () => {
      const frozen = renameOutcome(
        network(7, 'Baseline_v3_FINAL', 1, { editable: false }),
        network(7, 'Dual sourcing, EU', 1, { editable: false }),
      );

      expect(frozen).toContain('still frozen');
      expect(frozen).toContain('untouched');
    });
  });

  // ----------------------------------------------------------------------- refusals

  describe('renameRefusalNote', () => {
    it('explains a constraint violation as the (name, version) pair being taken', () => {
      // The server's own sentence is deliberately vague - a constraint name is an internal detail
      // - but only one constraint can have refused this request.
      const note = renameRefusalNote(ProblemCode.CONSTRAINT_VIOLATION) ?? '';

      expect(note).toContain('version number');
      expect(note).toContain('Duplicate network');
      expect(note).toContain('Nothing was changed');
    });

    it('answers DUPLICATE_NAME the same way', () => {
      expect(renameRefusalNote(ProblemCode.DUPLICATE_NAME)).toBe(
        renameRefusalNote(ProblemCode.CONSTRAINT_VIOLATION),
      );
    });

    it('reads NETWORK_IMMUTABLE as an out-of-date backend rather than a mistake here', () => {
      // The guard came off `NetworkService.update` for FR-29. Reaching this means the API predates
      // FR-29, which is worth one printed sentence rather than an afternoon.
      const note = renameRefusalNote(ProblemCode.NETWORK_IMMUTABLE) ?? '';

      expect(note).toContain('not a structural edit');
      expect(note).toContain('predates');
    });

    it('answers NOT_FOUND with the refresh', () => {
      expect(renameRefusalNote(ProblemCode.NOT_FOUND)).toContain('Refresh');
    });

    it('adds nothing to a code it does not know, or to none at all', () => {
      expect(renameRefusalNote('SOMETHING_NEW')).toBeNull();
      expect(renameRefusalNote(null)).toBeNull();
    });
  });
});
