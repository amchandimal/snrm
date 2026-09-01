import { ConfigurationVariant, Network } from '../../core/models';
import { buildProvenance, hasForks } from './provenance-tree';

function network(id: number, name: string, version: number, baseline = false): Network {
  return {
    id,
    projectId: 1,
    name,
    version,
    baseline,
    editable: true,
    periodLength: { value: 1, unit: 'DAY' },
    horizonPeriods: 30,
    roundingPolicy: 'UP',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

function variant(baseNetworkId: number, child: Network, note?: string): ConfigurationVariant {
  return {
    id: child.id * 100,
    projectId: 1,
    baseNetworkId,
    network: child,
    generatedBy: 'MANUAL',
    ...(note ? { leverChanges: { note } } : {}),
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

const labels = (entries: readonly { network: Network; depth: number }[]) =>
  entries.map((entry) => `${'  '.repeat(entry.depth)}${entry.network.name} v${entry.network.version}`);

describe('provenance-tree', () => {
  describe('buildProvenance', () => {
    it('places the baseline first and its forks beneath it', () => {
      const base = network(1, 'Baseline', 1, true);
      const dual = network(2, 'Dual source', 1);
      const buffer = network(3, 'Buffer', 1);

      const entries = buildProvenance(
        [dual, base, buffer],
        [variant(1, dual), variant(1, buffer)],
      );

      // Sorted by name within a level, so Buffer precedes Dual source.
      expect(labels(entries)).toEqual(['Baseline v1', '  Buffer v1', '  Dual source v1']);
      expect(entries[0].childCount).toBe(2);
      expect(entries[0].parent).toBeNull();
      expect(entries[2].parent).toBe(base);
    });

    it('nests a fork of a fork, which one variants call would not reveal', () => {
      const base = network(1, 'Baseline', 1, true);
      const second = network(2, 'Baseline', 2);
      const third = network(3, 'Baseline', 3);

      const entries = buildProvenance(
        [base, second, third],
        [variant(1, second), variant(2, third)],
      );

      expect(labels(entries)).toEqual(['Baseline v1', '  Baseline v2', '    Baseline v3']);
      expect(entries.map((entry) => entry.depth)).toEqual([0, 1, 2]);
    });

    it('marks the last sibling and rails the columns its ancestors still occupy', () => {
      const base = network(1, 'Baseline', 1, true);
      const a = network(2, 'A', 1);
      const b = network(3, 'B', 1);
      const aChild = network(4, 'A child', 1);

      const entries = buildProvenance(
        [base, a, b, aChild],
        [variant(1, a), variant(1, b), variant(2, aChild)],
      );

      expect(labels(entries)).toEqual(['Baseline v1', '  A v1', '    A child v1', '  B v1']);
      // A is not the last fork of the baseline, so its rail continues past its own child's row.
      expect(entries[1].last).toBe(false);
      expect(entries[2].rails).toEqual([true]);
      expect(entries[3].last).toBe(true);
      // Roots contribute no rail: two baselines are separate trees, not siblings on a line.
      expect(entries[0].rails).toEqual([]);
      expect(entries[1].rails).toEqual([]);
    });

    it('keeps the lever note on the row that was forked', () => {
      const base = network(1, 'Baseline', 1, true);
      const forked = network(2, 'Baseline', 2);

      const entries = buildProvenance([base, forked], [variant(1, forked, '+20% capacity at PLANT-1')]);

      expect(entries[0].variant).toBeNull();
      expect(entries[1].variant?.leverChanges).toEqual({ note: '+20% capacity at PLANT-1' });
    });

    it('shows a network whose base is missing as a root rather than dropping it', () => {
      // What a failed request in the sweep looks like from here: the edge was never learned.
      const orphan = network(2, 'Baseline', 2);

      const entries = buildProvenance([orphan], [variant(99, orphan)]);

      expect(labels(entries)).toEqual(['Baseline v2']);
      expect(entries[0].parent).toBeNull();
      expect(hasForks(entries)).toBe(false);
    });

    it('lists every network exactly once even if the parent pointers form a cycle', () => {
      // Impossible from the fork path - a copy always postdates its base - and therefore exactly
      // the shape a walk must not hang on.
      const a = network(1, 'A', 1);
      const b = network(2, 'B', 1);

      const entries = buildProvenance([a, b], [variant(2, a), variant(1, b)]);

      expect(entries.length).toBe(2);
      expect(entries.map((entry) => entry.network.id).sort()).toEqual([1, 2]);
    });

    it('ignores a variant that names itself as its own base', () => {
      const self = network(1, 'Baseline', 1, true);

      const entries = buildProvenance([self], [variant(1, self)]);

      expect(entries.length).toBe(1);
      expect(entries[0].depth).toBe(0);
      expect(entries[0].variant).toBeNull();
    });

    it('reports no forks when nothing was derived', () => {
      const entries = buildProvenance([network(1, 'Baseline', 1, true), network(2, 'Imported', 1)], []);

      expect(labels(entries)).toEqual(['Baseline v1', 'Imported v1']);
      expect(hasForks(entries)).toBe(false);
    });
  });
});
