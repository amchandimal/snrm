import { Network } from '../../core/models';
import { duplicateConfirm, duplicateOutcome } from './network-duplicate';
import { describeNetwork } from './network-selection';

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

describe('network-duplicate', () => {
  describe('duplicateConfirm', () => {
    const base = network(7, 'Baseline', 1);

    it('names the network it will fork, with its version and id', () => {
      // A name alone names every variant of it, which is the whole reason
      // `describeNetwork` exists - and it must not be spelled out a second way here.
      expect(duplicateConfirm(base).message).toContain(describeNetwork(base));
    });

    it('says the copy is a variant rather than an untracked copy, and where it will appear', () => {
      const message = duplicateConfirm(base).message;

      expect(message).toContain('variant');
      expect(message).toContain('untracked copy');
      expect(message).toContain('lineage');
    });

    it('promises the researcher is not navigated away (FR-26)', () => {
      expect(duplicateConfirm(base).message).toContain('stay on this page');
    });

    it('states that a configuration-variant record is written against the base', () => {
      // The tree is the test of the feature: without this row the copy is the one network in the
      // lineage whose parent nobody could name.
      const variantLine =
        duplicateConfirm(base).details.find((line) =>
          line.includes('configuration-variant record'),
        ) ?? '';

      expect(variantLine).toContain(describeNetwork(base));
    });

    it('states that the copy inherits the base network’s clock', () => {
      expect(duplicateConfirm(base).details.join(' ')).toContain('period length, horizon and');
    });

    it('states that the copy carries no runs and that the base is untouched', () => {
      const details = duplicateConfirm(base).details.join(' ');

      expect(details).toContain('No simulation runs');
      expect(details).toContain('changed, moved or removed');
    });

    it('names the version number the prefilled name will take', () => {
      // The default is not a placeholder but the intended way to take the next version
      // number and sort beside it, so the hint says which number that is.
      expect(duplicateConfirm(base).nameHint).toContain('Baseline v2');
    });

    it('warns that a different name starts its own version series', () => {
      expect(duplicateConfirm(base).nameHint).toContain('v1');
      expect(duplicateConfirm(base).nameHint).toContain('sorts elsewhere');
    });

    it('says a frozen network duplicates like any other, and why', () => {
      const frozen = duplicateConfirm(network(9, 'Baseline', 3, false)).details.join(' ');

      expect(frozen).toContain('frozen');
      expect(frozen).toContain('duplicates like any other');
      // The freeze covers *edits*; reading a configuration to copy it is not one.
      expect(frozen).toContain('edits');
    });

    it('says nothing about the freeze on an editable network', () => {
      expect(duplicateConfirm(base).details.join(' ')).not.toContain('frozen');
    });

    it('has no typed phrase, and the shape has nowhere to put one', () => {
      // Nothing is destroyed, so FR-15's typed discipline would be friction that teaches the user
      // to type through it - the judgement `selectionExportConfirm` already makes for FR-24.
      expect('requiredPhrase' in duplicateConfirm(base)).toBeFalse();
    });
  });

  describe('duplicateOutcome', () => {
    const base = network(7, 'Baseline', 1);

    it('names both networks and the fork between them', () => {
      const copy = network(31, 'Baseline', 2);

      const sentence = duplicateOutcome(base, copy);

      expect(sentence).toContain(describeNetwork(copy));
      expect(sentence).toContain(describeNetwork(base));
      expect(sentence).toContain('forked from');
    });

    it('says the copy sits beneath its base when it kept the name', () => {
      expect(duplicateOutcome(base, network(31, 'Baseline', 2))).toContain('next version number');
    });

    it('says where a renamed copy went instead, and that the lineage still holds', () => {
      const renamed = duplicateOutcome(base, network(31, 'Buffered', 1));

      expect(renamed).toContain('own version series');
      expect(renamed).toContain('lineage');
    });

    it('points at the lineage in both cases', () => {
      expect(duplicateOutcome(base, network(31, 'Baseline', 2))).toContain('lineage below');
      expect(duplicateOutcome(base, network(31, 'Buffered', 1))).toContain('lineage');
    });
  });
});
